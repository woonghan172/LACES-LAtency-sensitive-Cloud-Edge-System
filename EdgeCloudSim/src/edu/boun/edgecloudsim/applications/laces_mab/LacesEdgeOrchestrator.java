/*
 * Title:        EdgeCloudSim - Edge Orchestrator
 * 
 * Description: 
 * LacesEdgeOrchestrator offloads tasks to proper server
 * based on the applied scenario
 * 
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 * Copyright (c) 2022, Bogazici University, Istanbul, Turkey
 */

package edu.boun.edgecloudsim.applications.laces_mab;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.UtilizationModelFull;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;

import edu.boun.edgecloudsim.cloud_server.CloudVM;
import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_client.CpuUtilizationModel_Custom;
import edu.boun.edgecloudsim.edge_client.Task;
import edu.boun.edgecloudsim.edge_client.mobile_processing_unit.MobileVM;
import edu.boun.edgecloudsim.edge_orchestrator.EdgeOrchestrator;
import edu.boun.edgecloudsim.edge_server.EdgeVM;
import edu.boun.edgecloudsim.utils.SimLogger;
import edu.boun.edgecloudsim.utils.SimUtils;

/**
 * Responsibilities:
 * - Decide offloading target: mobile vs generic edge datacenter vs cloud
 * - Policies:
 *     LACES           : weighted heuristic score
 *     LACES_MAB       : LACES-guided contextual bandit with online reward update
 *     NETWORK_BASED   : prefer cloud if estimated uplink WAN BW > 5 Mbps
 *     UTILIZATION_BASED: prefer cloud if overall edge utilization > 75%
 *     RANDOM          : 50/50 coin flip between edge and cloud
 * - VM selection (both cloud and edge): Least Loaded (pick VM with largest residual CPU capacity that fits)
 */
public class LacesEdgeOrchestrator extends EdgeOrchestrator {
	private static final int ARM_MOBILE = 0;
	private static final int ARM_EDGE = 1;
	private static final int ARM_CLOUD = 2;
	private static final int ARM_COUNT = 3;

	private static final double MAB_EXPLORATION_COEFFICIENT = 0.35;
	private static final double LACES_PRIOR_WEIGHT = 0.35;
	private static final double FAILURE_PENALTY_SECONDS = 10.0;
	private static final double SCORE_EPSILON = 1e-9;

	private int numberOfHost;
	private final Map<ContextKey, BanditStats> banditStates;
	private final Map<Integer, PendingDecision> pendingDecisions;

	public LacesEdgeOrchestrator(String _policy, String _simScenario) {
		super(_policy, _simScenario);
		banditStates = new HashMap<ContextKey, BanditStats>();
		pendingDecisions = new HashMap<Integer, PendingDecision>();
	}

	@Override
	public void initialize() {
		numberOfHost = SimSettings.getInstance().getNumOfEdgeHosts();
	}

	@Override
	public int getDeviceToOffload(Task task) {
		Task dummyTask = new Task(0, 0, 0, 0, 128, 128,
				new UtilizationModelFull(), new UtilizationModelFull(), new UtilizationModelFull());

		double wanDelay = SimManager.getInstance().getNetworkModel().getUploadDelay(
				task.getMobileDeviceId(), SimSettings.CLOUD_DATACENTER_ID, dummyTask);
		double wanBW = (wanDelay == 0) ? 0 : (1 / wanDelay);
		double edgeUtilization = SimManager.getInstance().getEdgeServerManager().getAvgUtilization();

		if(policy.equals("NETWORK_BASED")){
			return (wanBW > 5) ? SimSettings.CLOUD_DATACENTER_ID : SimSettings.GENERIC_EDGE_DEVICE_ID;
		}
		else if(policy.equals("UTILIZATION_BASED")){
			return (edgeUtilization > 75)
					? SimSettings.CLOUD_DATACENTER_ID
					: SimSettings.GENERIC_EDGE_DEVICE_ID;
		}
		else if(policy.equals("RANDOM")){
			double randomNumber = SimUtils.getRandomDoubleNumber(0, 1);
			return (randomNumber < 0.5)
					? SimSettings.CLOUD_DATACENTER_ID
					: SimSettings.GENERIC_EDGE_DEVICE_ID;
		}
		else if(policy.equals("LACES")){
			return selectByLaces(task);
		}
		else if(policy.equals("LACES_MAB")){
			return selectByLacesMab(task);
		}

		SimLogger.printLine("Unknown edge orchestrator policy! Terminating simulation...");
		System.exit(0);
		return SimSettings.GENERIC_EDGE_DEVICE_ID;
	}

	@Override
	public Vm getVmToOffload(Task task, int deviceId) {
		Vm selectedVM = null;

		if(deviceId == SimSettings.MOBILE_DATACENTER_ID){
			selectedVM = selectMobileVm(task);
		}
		else if(deviceId == SimSettings.CLOUD_DATACENTER_ID){
			selectedVM = selectCloudVm(task);
		}
		else if(deviceId == SimSettings.GENERIC_EDGE_DEVICE_ID){
			selectedVM = selectEdgeVm(task);
		}
		else{
			SimLogger.printLine("Unknown device id! The simulation has been terminated.");
			System.exit(0);
		}

		return selectedVM;
	}

	public void recordTaskCompletion(Task task, double completionTime) {
		recordTaskOutcome(task, completionTime, true);
	}

	public void recordTaskFailure(Task task, double eventTime, String reason) {
		recordTaskOutcome(task, eventTime, false);
	}

	@Override
	public void processEvent(SimEvent arg0) {
		// Nothing to do.
	}

	@Override
	public void shutdownEntity() {
		// Nothing to do.
	}

	@Override
	public void startEntity() {
		// Nothing to do.
	}

	private int selectByLaces(Task task) {
		CandidateEvaluation evaluation = evaluateCandidates(task);
		return chooseBestLacesArm(evaluation);
	}

	private int selectByLacesMab(Task task) {
		CandidateEvaluation evaluation = evaluateCandidates(task);
		List<Integer> feasibleArms = evaluation.getFeasibleArms();
		if(feasibleArms.isEmpty())
			return chooseBestLacesArm(evaluation);

		ContextKey contextKey = buildContext(task, evaluation);
		BanditStats banditStats = getBanditStats(contextKey);

		int chosenArm = chooseUntriedArm(feasibleArms, banditStats, evaluation);
		if(chosenArm < 0)
			chosenArm = chooseBanditArm(feasibleArms, banditStats, evaluation);

		pendingDecisions.put(task.getCloudletId(), new PendingDecision(contextKey, chosenArm));
		return armToDeviceId(chosenArm);
	}

	private void recordTaskOutcome(Task task, double eventTime, boolean success) {
		if(!policy.equals("LACES_MAB"))
			return;

		PendingDecision decision = pendingDecisions.remove(task.getCloudletId());
		if(decision == null)
			return;

		BanditStats banditStats = banditStates.get(decision.contextKey);
		if(banditStats == null)
			return;

		double observedLatency = Math.max(0.0, eventTime - task.getCreationTime());
		double reward = success
				? -observedLatency
				: -Math.max(FAILURE_PENALTY_SECONDS, observedLatency);

		banditStats.armStats[decision.arm].update(reward, success);
	}

	private CandidateEvaluation evaluateCandidates(Task task){
		CandidateEvaluation evaluation = new CandidateEvaluation();

		evaluation.mobileVm = selectMobileVm(task);
		evaluation.edgeVm = selectEdgeVm(task);
		evaluation.cloudVm = selectCloudVm(task);

		Task netProbeTask = new Task(0, 0, 0, 0, 128, 128,
				new UtilizationModelFull(), new UtilizationModelFull(), new UtilizationModelFull());

		evaluation.wanDelay = SimManager.getInstance().getNetworkModel().getUploadDelay(
				task.getMobileDeviceId(), SimSettings.CLOUD_DATACENTER_ID, netProbeTask);
		evaluation.manDelay = SimManager.getInstance().getNetworkModel().getUploadDelay(
				SimSettings.GENERIC_EDGE_DEVICE_ID, SimSettings.GENERIC_EDGE_DEVICE_ID, netProbeTask);
		evaluation.edgeUtilization = SimManager.getInstance().getEdgeServerManager().getAvgUtilization();
		evaluation.payloadMbit = ((task.getCloudletFileSize() + task.getCloudletOutputSize()) * 8.0) / 1024.0;
		evaluation.predictedCpuDemand = getRepresentativeCpuDemand(task);

		double wanBwMbps = (evaluation.wanDelay > 0) ? (1.0 / evaluation.wanDelay) : 0.0;
		double manBwMbps = (evaluation.manDelay > 0) ? (1.0 / evaluation.manDelay) : 0.0;

		double[] lacesWeights = getLacesWeights(task);
		double wLatency = lacesWeights[0];
		double wComputation = lacesWeights[1];
		double wData = lacesWeights[2];
		double weightSum = wLatency + wComputation + wData;
		if(weightSum <= 0){
			wLatency = 1.0;
			wComputation = 0.0;
			wData = 0.0;
			weightSum = 1.0;
		}
		wLatency /= weightSum;
		wComputation /= weightSum;
		wData /= weightSum;

		double tNetDevice = 0.0;
		double tNetEdge = (evaluation.manDelay > 0) ? evaluation.manDelay : Double.MAX_VALUE;
		double tNetCloud = (evaluation.wanDelay > 0) ? evaluation.wanDelay : Double.MAX_VALUE;

		double tProcessingDevice = getProcessingCost(task, evaluation.mobileVm);
		double tProcessingEdge = getProcessingCost(task, evaluation.edgeVm);
		double tProcessingCloud = getProcessingCost(task, evaluation.cloudVm);

		double tTransmissionDevice = 0.0;
		double tTransmissionEdge = (manBwMbps > 0) ? (evaluation.payloadMbit / manBwMbps) : Double.MAX_VALUE;
		double tTransmissionCloud = (wanBwMbps > 0) ? (evaluation.payloadMbit / wanBwMbps) : Double.MAX_VALUE;

		evaluation.scores[ARM_MOBILE] =
				(wLatency * tNetDevice) +
				(wComputation * tProcessingDevice) +
				(wData * tTransmissionDevice);
		evaluation.scores[ARM_EDGE] =
				(wLatency * tNetEdge) +
				(wComputation * tProcessingEdge) +
				(wData * tTransmissionEdge);
		evaluation.scores[ARM_CLOUD] =
				(wLatency * tNetCloud) +
				(wComputation * tProcessingCloud) +
				(wData * tTransmissionCloud);

		if(evaluation.mobileVm == null)
			evaluation.scores[ARM_MOBILE] = Double.POSITIVE_INFINITY;
		if(evaluation.edgeVm == null)
			evaluation.scores[ARM_EDGE] = Double.POSITIVE_INFINITY;
		if(evaluation.cloudVm == null)
			evaluation.scores[ARM_CLOUD] = Double.POSITIVE_INFINITY;

		return evaluation;
	}

	private int chooseBestLacesArm(CandidateEvaluation evaluation) {
		List<Integer> feasibleArms = evaluation.getFeasibleArms();
		if(feasibleArms.isEmpty()) {
			if(evaluation.mobileVm != null)
				return SimSettings.MOBILE_DATACENTER_ID;
			if(evaluation.edgeVm != null)
				return SimSettings.GENERIC_EDGE_DEVICE_ID;
			return SimSettings.CLOUD_DATACENTER_ID;
		}

		int bestArm = feasibleArms.get(0);
		double bestScore = evaluation.getScore(bestArm);
		for(int i = 1; i < feasibleArms.size(); i++) {
			int arm = feasibleArms.get(i);
			double score = evaluation.getScore(arm);
			if(score < bestScore) {
				bestScore = score;
				bestArm = arm;
			}
		}

		return armToDeviceId(bestArm);
	}

	private int chooseUntriedArm(List<Integer> feasibleArms, BanditStats banditStats, CandidateEvaluation evaluation) {
		int chosenArm = -1;
		double bestLacesScore = Double.POSITIVE_INFINITY;
		for(Integer arm : feasibleArms) {
			if(banditStats.armStats[arm].pullCount > 0)
				continue;

			double score = evaluation.getScore(arm);
			if(score < bestLacesScore) {
				bestLacesScore = score;
				chosenArm = arm;
			}
		}
		return chosenArm;
	}

	private int chooseBanditArm(List<Integer> feasibleArms, BanditStats banditStats, CandidateEvaluation evaluation) {
		int totalPulls = 0;
		for(Integer arm : feasibleArms)
			totalPulls += Math.max(1, banditStats.armStats[arm].pullCount);

		double minScore = Double.POSITIVE_INFINITY;
		double maxScore = Double.NEGATIVE_INFINITY;
		for(Integer arm : feasibleArms) {
			double score = evaluation.getScore(arm);
			if(score < minScore)
				minScore = score;
			if(score > maxScore)
				maxScore = score;
		}

		int bestArm = feasibleArms.get(0);
		double bestScore = Double.NEGATIVE_INFINITY;
		for(Integer arm : feasibleArms) {
			ArmStat stat = banditStats.armStats[arm];
			double exploration = MAB_EXPLORATION_COEFFICIENT *
					Math.sqrt(Math.log(Math.max(2, totalPulls)) / stat.pullCount);

			double lacesPrior;
			if(maxScore - minScore <= SCORE_EPSILON)
				lacesPrior = 1.0;
			else
				lacesPrior = 1.0 - ((evaluation.getScore(arm) - minScore) / (maxScore - minScore));

			double score = stat.meanReward + exploration + (LACES_PRIOR_WEIGHT * lacesPrior);
			if(score > bestScore || (Math.abs(score - bestScore) <= SCORE_EPSILON && evaluation.getScore(arm) < evaluation.getScore(bestArm))) {
				bestScore = score;
				bestArm = arm;
			}
		}

		return bestArm;
	}

	private BanditStats getBanditStats(ContextKey contextKey) {
		BanditStats banditStats = banditStates.get(contextKey);
		if(banditStats == null) {
			banditStats = new BanditStats();
			banditStates.put(contextKey, banditStats);
		}
		return banditStats;
	}

	private ContextKey buildContext(Task task, CandidateEvaluation evaluation) {
		return new ContextKey(
				task.getTaskType(),
				bucketizePayload(evaluation.payloadMbit),
				bucketizeCpu(evaluation.predictedCpuDemand),
				bucketizeWanDelay(evaluation.wanDelay),
				bucketizeEdgeUtilization(evaluation.edgeUtilization)
		);
	}

	private double getRepresentativeCpuDemand(Task task) {
		if(!(task.getUtilizationModelCpu() instanceof CpuUtilizationModel_Custom))
			return 0.0;

		CpuUtilizationModel_Custom model = (CpuUtilizationModel_Custom)task.getUtilizationModelCpu();
		return model.predictUtilization(SimSettings.VM_TYPES.EDGE_VM);
	}

	private int bucketizePayload(double payloadMbit) {
		if(payloadMbit < 1.0)
			return 0;
		if(payloadMbit < 8.0)
			return 1;
		return 2;
	}

	private int bucketizeCpu(double cpuDemand) {
		if(cpuDemand < 25.0)
			return 0;
		if(cpuDemand < 60.0)
			return 1;
		return 2;
	}

	private int bucketizeWanDelay(double wanDelay) {
		if(wanDelay <= 0)
			return 2;
		if(wanDelay < 0.03)
			return 0;
		if(wanDelay < 0.08)
			return 1;
		return 2;
	}

	private int bucketizeEdgeUtilization(double edgeUtilization) {
		if(edgeUtilization < 10.0)
			return 0;
		if(edgeUtilization < 30.0)
			return 1;
		return 2;
	}

	private int armToDeviceId(int arm) {
		switch (arm) {
			case ARM_MOBILE:
				return SimSettings.MOBILE_DATACENTER_ID;
			case ARM_EDGE:
				return SimSettings.GENERIC_EDGE_DEVICE_ID;
			case ARM_CLOUD:
				return SimSettings.CLOUD_DATACENTER_ID;
			default:
				return SimSettings.GENERIC_EDGE_DEVICE_ID;
		}
	}

	private Vm selectMobileVm(Task task){
		if(SimSettings.getInstance().getCoreForMobileVM() <= 0 || SimSettings.getInstance().getMipsForMobileVM() <= 0)
			return null;

		List<MobileVM> vmArray = SimManager.getInstance().getMobileServerManager().getVmList(task.getMobileDeviceId());
		if(vmArray == null || vmArray.isEmpty())
			return null;

		MobileVM vm = vmArray.get(0);
		double requiredCapacity = ((CpuUtilizationModel_Custom)task.getUtilizationModelCpu()).predictUtilization(vm.getVmType());
		double targetVmCapacity = (double)100 - vm.getCloudletScheduler().getTotalUtilizationOfCpu(CloudSim.clock());
		if(requiredCapacity <= targetVmCapacity)
			return vm;

		return null;
	}

	private Vm selectCloudVm(Task task){
		Vm selectedVM = null;
		double selectedVmCapacity = 0;

		List<Host> list = SimManager.getInstance().getCloudServerManager().getDatacenter().getHostList();
		for (int hostIndex=0; hostIndex < list.size(); hostIndex++) {
			List<CloudVM> vmArray = SimManager.getInstance().getCloudServerManager().getVmList(hostIndex);
			for(int vmIndex=0; vmIndex<vmArray.size(); vmIndex++){
				double requiredCapacity = ((CpuUtilizationModel_Custom)task.getUtilizationModelCpu()).predictUtilization(vmArray.get(vmIndex).getVmType());
				double targetVmCapacity = (double)100 - vmArray.get(vmIndex).getCloudletScheduler().getTotalUtilizationOfCpu(CloudSim.clock());
				if(requiredCapacity <= targetVmCapacity && targetVmCapacity > selectedVmCapacity){
					selectedVM = vmArray.get(vmIndex);
					selectedVmCapacity = targetVmCapacity;
				}
			}
		}

		return selectedVM;
	}

	private Vm selectEdgeVm(Task task){
		Vm selectedVM = null;
		double selectedVmCapacity = 0;

		for(int hostIndex=0; hostIndex<numberOfHost; hostIndex++){
			List<EdgeVM> vmArray = SimManager.getInstance().getEdgeServerManager().getVmList(hostIndex);
			for(int vmIndex=0; vmIndex<vmArray.size(); vmIndex++){
				double requiredCapacity = ((CpuUtilizationModel_Custom)task.getUtilizationModelCpu()).predictUtilization(vmArray.get(vmIndex).getVmType());
				double targetVmCapacity = (double)100 - vmArray.get(vmIndex).getCloudletScheduler().getTotalUtilizationOfCpu(CloudSim.clock());
				if(requiredCapacity <= targetVmCapacity && targetVmCapacity > selectedVmCapacity){
					selectedVM = vmArray.get(vmIndex);
					selectedVmCapacity = targetVmCapacity;
				}
			}
		}

		return selectedVM;
	}

	private double[] getLacesWeights(Task task){
		double[][] weightTable = {
			{0.1, 0.8, 0.1},
			{0.8, 0.2, 0.0},
			{0.0, 0.9, 0.1}
		};

		int taskType = task.getTaskType();
		if(taskType >= 0 && taskType < weightTable.length)
			return weightTable[taskType];

		return new double[] {
			SimSettings.getInstance().getLacesWeightLatency(),
			SimSettings.getInstance().getLacesWeightComputation(),
			SimSettings.getInstance().getLacesWeightData()
		};
	}

	private double getProcessingCost(Task task, Vm vm){
		if(vm == null)
			return Double.MAX_VALUE;

		SimSettings.VM_TYPES vmType;
		if(vm instanceof EdgeVM)
			vmType = ((EdgeVM)vm).getVmType();
		else if(vm instanceof CloudVM)
			vmType = ((CloudVM)vm).getVmType();
		else if(vm instanceof MobileVM)
			vmType = ((MobileVM)vm).getVmType();
		else
			return Double.MAX_VALUE;

		double requiredCapacity = ((CpuUtilizationModel_Custom)task.getUtilizationModelCpu()).predictUtilization(vmType);
		double targetVmCapacity = (double)100 - vm.getCloudletScheduler().getTotalUtilizationOfCpu(CloudSim.clock());
		if(targetVmCapacity <= 0)
			return Double.MAX_VALUE;

		return requiredCapacity / targetVmCapacity;
	}

	private static final class CandidateEvaluation {
		private final double[] scores;
		private Vm mobileVm;
		private Vm edgeVm;
		private Vm cloudVm;
		private double payloadMbit;
		private double predictedCpuDemand;
		private double wanDelay;
		private double manDelay;
		private double edgeUtilization;

		private CandidateEvaluation() {
			scores = new double[] {
				Double.POSITIVE_INFINITY,
				Double.POSITIVE_INFINITY,
				Double.POSITIVE_INFINITY
			};
		}

		private List<Integer> getFeasibleArms() {
			List<Integer> feasibleArms = new ArrayList<Integer>();
			for(int arm = 0; arm < ARM_COUNT; arm++) {
				if(!Double.isInfinite(scores[arm]))
					feasibleArms.add(arm);
			}
			return feasibleArms;
		}

		private double getScore(int arm) {
			return scores[arm];
		}
	}

	private static final class PendingDecision {
		private final ContextKey contextKey;
		private final int arm;

		private PendingDecision(ContextKey contextKey, int arm) {
			this.contextKey = contextKey;
			this.arm = arm;
		}
	}

	private static final class BanditStats {
		private final ArmStat[] armStats;

		private BanditStats() {
			armStats = new ArmStat[] { new ArmStat(), new ArmStat(), new ArmStat() };
		}
	}

	private static final class ArmStat {
		private int pullCount;
		private int successCount;
		private int failureCount;
		private double meanReward;

		private ArmStat() {
			pullCount = 0;
			successCount = 0;
			failureCount = 0;
			meanReward = 0.0;
		}

		private void update(double reward, boolean success) {
			pullCount++;
			meanReward += (reward - meanReward) / pullCount;
			if(success)
				successCount++;
			else
				failureCount++;
		}
	}

	private static final class ContextKey {
		private final int taskType;
		private final int payloadBin;
		private final int cpuBin;
		private final int wanDelayBin;
		private final int edgeUtilizationBin;

		private ContextKey(int taskType, int payloadBin, int cpuBin, int wanDelayBin, int edgeUtilizationBin) {
			this.taskType = taskType;
			this.payloadBin = payloadBin;
			this.cpuBin = cpuBin;
			this.wanDelayBin = wanDelayBin;
			this.edgeUtilizationBin = edgeUtilizationBin;
		}

		@Override
		public boolean equals(Object obj) {
			if(this == obj)
				return true;
			if(!(obj instanceof ContextKey))
				return false;
			ContextKey other = (ContextKey)obj;
			return taskType == other.taskType &&
					payloadBin == other.payloadBin &&
					cpuBin == other.cpuBin &&
					wanDelayBin == other.wanDelayBin &&
					edgeUtilizationBin == other.edgeUtilizationBin;
		}

		@Override
		public int hashCode() {
			int result = 17;
			result = 31 * result + taskType;
			result = 31 * result + payloadBin;
			result = 31 * result + cpuBin;
			result = 31 * result + wanDelayBin;
			result = 31 * result + edgeUtilizationBin;
			return result;
		}
	}
}
