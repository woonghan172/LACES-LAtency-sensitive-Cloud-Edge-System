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

package edu.boun.edgecloudsim.applications.laces;

import java.util.List;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.UtilizationModelFull;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;

import edu.boun.edgecloudsim.cloud_server.CloudVM;
import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_orchestrator.EdgeOrchestrator;
import edu.boun.edgecloudsim.edge_server.EdgeVM;
import edu.boun.edgecloudsim.edge_client.CpuUtilizationModel_Custom;
import edu.boun.edgecloudsim.edge_client.Task;
import edu.boun.edgecloudsim.edge_client.mobile_processing_unit.MobileVM;
import edu.boun.edgecloudsim.utils.SimLogger;
import edu.boun.edgecloudsim.utils.SimUtils;

/**
 * Responsibilities:
 * - Decide offloading target: cloud vs generic edge datacenter
 * - Policies:
 * 	   LACES			  : Latency sensitive
 *     NETWORK_BASED      : prefer cloud if estimated uplink WAN BW > 5 Mbps
 *     UTILIZATION_BASED  : prefer cloud if overall edge utilization > 75%
 *     RANDOM             : 50/50 coin flip
 * - VM selection (both cloud and edge): Least Loaded (pick VM with largest residual CPU capacity that fits)
 * Capacity definitions:
 *     requiredCapacity = predicted CPU % for task on VM type
 *     targetVmCapacity = 100 - current utilized CPU %
 * Returns null if no VM can host task (caller handles rejection).
 */
public class LacesEdgeOrchestrator extends EdgeOrchestrator {
	// numberOfHost cached to avoid repeated settings lookups
	private int numberOfHost; //used by load balancer

	public LacesEdgeOrchestrator(String _policy, String _simScenario) {
		super(_policy, _simScenario);
	}

	@Override
	public void initialize() {
		// Cache total edge host count for iteration in getVmToOffload
		numberOfHost=SimSettings.getInstance().getNumOfEdgeHosts();
	}

	/*
	 * (non-Javadoc)
	 * @see edu.boun.edgecloudsim.edge_orchestrator.EdgeOrchestrator#getDeviceToOffload(edu.boun.edgecloudsim.edge_client.Task)
	 * 
	 * It is assumed that the edge orchestrator app is running on the edge devices in a distributed manner
	 */
	@Override
	public int getDeviceToOffload(Task task) {
		int result = 0;

		// Create a tiny dummy task (128 KB up/down ~ 1 Mbit total each way) to probe WAN delay
		// Used to estimate available WAN bandwidth = transferred_bits / delay
		// (Simplistic instantaneous probe; ignores contention dynamics)
		Task dummyTask = new Task(0, 0, 0, 0, 128, 128, new UtilizationModelFull(), new UtilizationModelFull(), new UtilizationModelFull());
		
		double wanDelay = SimManager.getInstance().getNetworkModel().getUploadDelay(task.getMobileDeviceId(),
				SimSettings.CLOUD_DATACENTER_ID, dummyTask /* 1 Mbit */);
		double wanBW = (wanDelay == 0) ? 0 : (1 / wanDelay); /* Mbps (since dummy payload ~1 Mbit) */

		// Aggregate edge utilization across all edge VMs (percentage basis)
		double edgeUtilization = SimManager.getInstance().getEdgeServerManager().getAvgUtilization();
		

		if(policy.equals("NETWORK_BASED")){
			// Threshold heuristic: if WAN > 5 Mbps offload to cloud else stay at edge
			if(wanBW > 5)
				result = SimSettings.CLOUD_DATACENTER_ID;
			else
				result = SimSettings.GENERIC_EDGE_DEVICE_ID;
		}
		else if(policy.equals("UTILIZATION_BASED")){
			// If edge utilization exceeds 75%, divert load to cloud to prevent saturation
			double utilization = edgeUtilization;
			if(utilization > 75)
				result = SimSettings.CLOUD_DATACENTER_ID;
			else
				result = SimSettings.GENERIC_EDGE_DEVICE_ID;
		}
		else if(policy.equals("RANDOM")){
			// Fair random split between edge and cloud resources
			double randomNumber = SimUtils.getRandomDoubleNumber(0, 1);
			if(randomNumber < 0.5)
				result = SimSettings.CLOUD_DATACENTER_ID;
			else
				result = SimSettings.GENERIC_EDGE_DEVICE_ID;
		}
		else if(policy.equals("LACES")){
			// LACES weighted-cost policy:
			// score = w_latency*T_net + w_computation*T_processing + w_data*T_transmission
			// lower score is better. Candidate targets: Device, Edge, Cloud.

			Vm mobileVm = selectMobileVm(task);
			Vm edgeVm = selectEdgeVm(task);
			Vm cloudVm = selectCloudVm(task);

			Task netProbeTask = new Task(0, 0, 0, 0, 128, 128,
					new UtilizationModelFull(), new UtilizationModelFull(), new UtilizationModelFull());

			// User-requested latency mapping:
			// - Device: no network latency
			// - Edge: MAN latency probe
			// - Cloud: WAN latency probe
			double wanProbeDelay = SimManager.getInstance().getNetworkModel().getUploadDelay(
					task.getMobileDeviceId(), SimSettings.CLOUD_DATACENTER_ID, netProbeTask);
			double manDelay = SimManager.getInstance().getNetworkModel().getUploadDelay(
					SimSettings.GENERIC_EDGE_DEVICE_ID, SimSettings.GENERIC_EDGE_DEVICE_ID, netProbeTask);

			double payloadMbit = ((task.getCloudletFileSize() + task.getCloudletOutputSize()) * 8.0) / 1024.0;
			double wanBwMbps = (wanProbeDelay > 0) ? (1.0 / wanProbeDelay) : 0.0;
			double manBwMbps = (manDelay > 0) ? (1.0 / manDelay) : 0.0;

			double tNetDevice = 0.0;
			double tNetEdge = (manDelay > 0) ? manDelay : Double.MAX_VALUE;
			double tNetCloud = (wanProbeDelay > 0) ? wanProbeDelay : Double.MAX_VALUE;

			double tProcessingDevice = getProcessingCost(task, mobileVm);
			double tProcessingEdge = getProcessingCost(task, edgeVm);
			double tProcessingCloud = getProcessingCost(task, cloudVm);

			double tTransmissionDevice = 0.0;
			double tTransmissionEdge = (manBwMbps > 0) ? (payloadMbit / manBwMbps) : Double.MAX_VALUE;
			double tTransmissionCloud = (wanBwMbps > 0) ? (payloadMbit / wanBwMbps) : Double.MAX_VALUE;

			double wLatency = SimSettings.getInstance().getLacesWeightLatency();
			double wComputation = SimSettings.getInstance().getLacesWeightComputation();
			double wData = SimSettings.getInstance().getLacesWeightData();
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

			double deviceScore =
					(wLatency * tNetDevice) +
					(wComputation * tProcessingDevice) +
					(wData * tTransmissionDevice);

			double edgeScore =
					(wLatency * tNetEdge) +
					(wComputation * tProcessingEdge) +
					(wData * tTransmissionEdge);

			double cloudScore =
					(wLatency * tNetCloud) +
					(wComputation * tProcessingCloud) +
					(wData * tTransmissionCloud);

			// Guard feasibility: if target VM is not available, force score to +inf.
			if(mobileVm == null)
				deviceScore = Double.POSITIVE_INFINITY;
			if(edgeVm == null)
				edgeScore = Double.POSITIVE_INFINITY;
			if(cloudVm == null)
				cloudScore = Double.POSITIVE_INFINITY;

			if(Double.isInfinite(deviceScore) && Double.isInfinite(edgeScore) && Double.isInfinite(cloudScore)) {
				if(mobileVm != null)
					result = SimSettings.MOBILE_DATACENTER_ID;
				else if(edgeVm != null)
					result = SimSettings.GENERIC_EDGE_DEVICE_ID;
				else
					result = SimSettings.CLOUD_DATACENTER_ID;
			}
			else {
				result = SimSettings.MOBILE_DATACENTER_ID;
				double bestScore = deviceScore;

				if(edgeScore < bestScore){
					bestScore = edgeScore;
					result = SimSettings.GENERIC_EDGE_DEVICE_ID;
				}

				if(cloudScore < bestScore){
					result = SimSettings.CLOUD_DATACENTER_ID;
				}
			}
		}
		else {
			// Unknown policy => configuration error
			SimLogger.printLine("Unknown edge orchestrator policy! Terminating simulation...");
			System.exit(0);
		}

		return result;
	}

	@Override
	public Vm getVmToOffload(Task task, int deviceId) {
		// Select VM with maximum residual capacity that can host predicted load (Least Loaded / WORST FIT)
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
			// Defensive: unexpected device id
			SimLogger.printLine("Unknown device id! The simulation has been terminated.");
			System.exit(0);
		}
		
		return selectedVM;
	}

	@Override
	public void processEvent(SimEvent arg0) {
		// No asynchronous internal events required (stateless orchestrator)
		// Nothing to do!
	}

	@Override
	public void shutdownEntity() {
		// No resources to release
		// Nothing to do!
	}

	@Override
	public void startEntity() {
		// No startup scheduling needed
		// Nothing to do!
	}

	private Vm selectMobileVm(Task task){
		// If mobile processing is disabled in config, do not consider device target.
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

}