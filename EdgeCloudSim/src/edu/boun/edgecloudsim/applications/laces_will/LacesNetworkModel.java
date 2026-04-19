/*
 * Title:        EdgeCloudSim - Network Model
 * 
 * Description: 
 * LacesNetworkModel uses
 * -> the result of an empirical study for the WLAN and WAN delays
 * The experimental network model is developed
 * by taking measurements from the real life deployments.
 *   
 * -> MMPP/MMPP/1 queue model for MAN delay
 * MAN delay is observed via a single server queue model with
 * Markov-modulated Poisson process (MMPP) arrivals.
 *   
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 * Copyright (c) 2017, Bogazici University, Istanbul, Turkey
 */

package edu.boun.edgecloudsim.applications.laces_will;

import org.cloudbus.cloudsim.core.CloudSim;

import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_client.Task;
import edu.boun.edgecloudsim.network.NetworkModel;
import edu.boun.edgecloudsim.utils.Location;
import edu.boun.edgecloudsim.utils.SimLogger;

public class LacesNetworkModel extends NetworkModel {
	public static enum NETWORK_TYPE {WLAN, LAN};
	public static enum LINK_TYPE {DOWNLOAD, UPLOAD};

	@SuppressWarnings("unused")
	private int manClients; // concurrent MAN relay sessions (aggregate)
	private int[] wanClients;
	private int[] wlanClients;

	private double lastMM1QueueUpdateTime; // last time we reset MAN statistics
	private double ManPoissonMeanForDownload; // average inter-arrival (s) for MAN downloads (adaptive)
	private double ManPoissonMeanForUpload;   // average inter-arrival (s) for MAN uploads (adaptive)

	private double avgManTaskInputSize;  // average MAN task input size (KB) over last window
	private double avgManTaskOutputSize; // average MAN task output size (KB) over last window

	//record last n task statistics during MM1_QUEUE_MODEL_UPDATE_INTEVAL seconds to simulate mmpp/m/1 queue model
	private double totalManTaskInputSize;     // cumulative input KB this window
	private double totalManTaskOutputSize;    // cumulative output KB this window
	private double numOfManTaskForDownload;   // download task count this window
	private double numOfManTaskForUpload;     // upload task count this window

	// Updated for US 2026 Context: 1Gbps Metropolitan Fiber Backhaul
	public static double MAN_BW = 1000 * 1024 * 1024; // 1,048,576,000 bps


	/*
	This array reflects the performance of a high-end US router (802.11ax or 802.11be).

	Baseline (1 Client): Set to 850,000.0 Kbps (~850 Mbps). This represents the real-world high-end throughput of a Wi-Fi 6 router in a US residential or office setting.

	Initial Scaling (2–10 Clients): The values drop sharply ($1/N$ curve) because even modern Wi-Fi must divide the available airtime among active clients. For example, 2 clients get ~430 Mbps each, and 10 clients get ~105 Mbps each.

	Congestion Management (11–100 Clients): Unlike older standards (802.11n), Wi-Fi 6 uses OFDMA (Orthogonal Frequency Division Multiple Access), which allows the router to talk to multiple clients simultaneously.

	The Curve: Because of OFDMA, the "drop-off" slows down as you reach higher client counts. Instead of the speed crashing to zero, it levels off to around 14-15 Mbps per client at the 100-user mark, ensuring basic connectivity even under heavy load.
	*/
    public static final double[] experimentalWlanDelay = {
		/*1 clients*/   850000.0 /*(Kbps)*/,
		/*2 clients*/   430000.0,
		/*3 clients*/   290000.0,
		/*4 clients*/   220000.0,
		/*5 clients*/   185000.0,
		/*6 clients*/   160000.0,
		/*7 clients*/   140000.0,
		/*8 clients*/   125000.0,
		/*9 clients*/   115000.0,
		/*10 clients*/  105000.0,
		/*11 clients*/  98000.0,
		/*12 clients*/  92000.0,
		/*13 clients*/  87000.0,
		/*14 clients*/  82000.0,
		/*15 clients*/  78000.0,
		/*16 clients*/  74000.0,
		/*17 clients*/  71000.0,
		/*18 clients*/  68000.0,
		/*19 clients*/  65000.0,
		/*20 clients*/  62000.0,
		/*21 clients*/  60000.0,
		/*22 clients*/  58000.0,
		/*23 clients*/  56000.0,
		/*24 clients*/  54000.0,
		/*25 clients*/  52000.0,
		/*26 clients*/  50000.0,
		/*27 clients*/  48500.0,
		/*28 clients*/  47000.0,
		/*29 clients*/  45500.0,
		/*30 clients*/  44000.0,
		/*31 clients*/  43000.0,
		/*32 clients*/  42000.0,
		/*33 clients*/  41000.0,
		/*34 clients*/  40000.0,
		/*35 clients*/  39000.0,
		/*36 clients*/  38000.0,
		/*37 clients*/  37000.0,
		/*38 clients*/  36500.0,
		/*39 clients*/  36000.0,
		/*40 clients*/  35500.0,
		/*41 clients*/  35000.0,
		/*42 clients*/  34500.0,
		/*43 clients*/  34000.0,
		/*44 clients*/  33500.0,
		/*45 clients*/  33000.0,
		/*46 clients*/  32500.0,
		/*47 clients*/  32000.0,
		/*48 clients*/  31500.0,
		/*49 clients*/  31000.0,
		/*50 clients*/  30500.0,
		/*51 clients*/  30000.0,
		/*52 clients*/  29500.0,
		/*53 clients*/  29000.0,
		/*54 clients*/  28500.0,
		/*55 clients*/  28000.0,
		/*56 clients*/  27500.0,
		/*57 clients*/  27000.0,
		/*58 clients*/  26500.0,
		/*59 clients*/  26000.0,
		/*60 clients*/  25500.0,
		/*61 clients*/  25000.0,
		/*62 clients*/  24500.0,
		/*63 clients*/  24000.0,
		/*64 clients*/  23500.0,
		/*65 clients*/  23000.0,
		/*66 clients*/  22500.0,
		/*67 clients*/  22000.0,
		/*68 clients*/  21500.0,
		/*69 clients*/  21000.0,
		/*70 clients*/  20500.0,
		/*71 clients*/  20000.0,
		/*72 clients*/  19800.0,
		/*73 clients*/  19600.0,
		/*74 clients*/  19400.0,
		/*75 clients*/  19200.0,
		/*76 clients*/  19000.0,
		/*77 clients*/  18800.0,
		/*78 clients*/  18600.0,
		/*79 clients*/  18400.0,
		/*80 clients*/  18200.0,
		/*81 clients*/  18000.0,
		/*82 clients*/  17800.0,
		/*83 clients*/  17600.0,
		/*84 clients*/  17400.0,
		/*85 clients*/  17200.0,
		/*86 clients*/  17000.0,
		/*87 clients*/  16800.0,
		/*88 clients*/  16600.0,
		/*89 clients*/  16400.0,
		/*90 clients*/  16200.0,
		/*91 clients*/  16000.0,
		/*92 clients*/  15800.0,
		/*93 clients*/  15600.0,
		/*94 clients*/  15400.0,
		/*95 clients*/  15200.0,
		/*96 clients*/  15000.0,
		/*97 clients*/  14800.0,
		/*98 clients*/  14600.0,
		/*99 clients*/  14400.0,
		/*100 clients*/ 14200.0
	};
	
	// WAN: US Median Broadband (Kbps)
	// Source: Ookla/FCC 2026 Benchmarks (308 Mbps Median)

	/*
	This array reflects the sharing of a standard US "last-mile" internet connection (Fiber or high-speed Cable).

	Baseline (1 Client): Set to 315,496.0 Kbps (~308-315 Mbps). This matches the 2026 US median fixed broadband download speed.

	Linear Degradation (1–25 Clients): Since a household or small office WAN pipe has a fixed capacity (e.g., 300 Mbps), the values follow a strictly competitive model:

	2 Clients: Each gets roughly half (~160 Mbps).

	5 Clients: Each gets ~70 Mbps.

	25 Clients: Each gets ~15 Mbps.

	Lower Bound: The 25th client is set at 15,000 Kbps (15 Mbps), which is the minimum threshold often considered "functional" for modern web tasks in the US.
	*/
	public static final double[] experimentalWanDelay = {
		/*1 clients*/  315496.0 /*(Kbps)*/,
		/*2 clients*/  160000.0,
		/*3 clients*/  110000.0,
		/*4 clients*/  85000.0,
		/*5 clients*/  70000.0,
		/*6 clients*/  60000.0,
		/*7 clients*/  52000.0,
		/*8 clients*/  46000.0,
		/*9 clients*/  41000.0,
		/*10 clients*/ 37000.0,
		/*11 clients*/ 34000.0,
		/*12 clients*/ 31000.0,
		/*13 clients*/ 29000.0,
		/*14 clients*/ 27000.0,
		/*15 clients*/ 25000.0,
		/*16 clients*/ 23500.0,
		/*17 clients*/ 22000.0,
		/*18 clients*/ 21000.0,
		/*19 clients*/ 20000.0,
		/*20 clients*/ 19000.0,
		/*21 clients*/ 18000.0,
		/*22 clients*/ 17000.0,
		/*23 clients*/ 16500.0,
		/*24 clients*/ 16000.0,
		/*25 clients*/ 15000.0
	};
	
	public LacesNetworkModel(int _numberOfMobileDevices, String _simScenario) {
		super(_numberOfMobileDevices, _simScenario);
	}

	@Override
	public void initialize() {
		// Initialize per-AP arrays (one AP per edge datacenter)
		// Derive initial MAN queue means from task lookup table (weighted by usage percentages)
		// Assumption: half tasks initially traverse MAN (factor 4 used as heuristic scaling)
		wanClients = new int[SimSettings.getInstance().getNumOfEdgeDatacenters()];  //we have one access point for each datacenter
		wlanClients = new int[SimSettings.getInstance().getNumOfEdgeDatacenters()];  //we have one access point for each datacenter

		int numOfApp = SimSettings.getInstance().getTaskLookUpTable().length;
		SimSettings SS = SimSettings.getInstance();
		int activeAppCount = 0;
		for(int taskIndex=0; taskIndex<numOfApp; taskIndex++) {
			if(SS.getTaskLookUpTable()[taskIndex][0] == 0) {
				// Allow zero-usage task types for single-app experiments.
				continue;
			}
			else{
				activeAppCount++;
				double weight = SS.getTaskLookUpTable()[taskIndex][0]/(double)100;
				
				//assume half of the tasks use the MAN at the beginning
				ManPoissonMeanForDownload += ((SS.getTaskLookUpTable()[taskIndex][2])*weight) * 4;
				ManPoissonMeanForUpload = ManPoissonMeanForDownload;
				
				avgManTaskInputSize += SS.getTaskLookUpTable()[taskIndex][5]*weight;
				avgManTaskOutputSize += SS.getTaskLookUpTable()[taskIndex][6]*weight;
			}
		}

		if(activeAppCount == 0){
			SimLogger.printLine("No active application found in applications.xml! Terminating simulation...");
			System.exit(0);
		}

		ManPoissonMeanForDownload = ManPoissonMeanForDownload/numOfApp;
		ManPoissonMeanForUpload = ManPoissonMeanForUpload/numOfApp;
		avgManTaskInputSize = avgManTaskInputSize/numOfApp;
		avgManTaskOutputSize = avgManTaskOutputSize/numOfApp;
		
		// Initialize rolling window statistics
		lastMM1QueueUpdateTime = SimSettings.CLIENT_ACTIVITY_START_TIME;
		totalManTaskOutputSize = 0;
		numOfManTaskForDownload = 0;
		totalManTaskInputSize = 0;
		numOfManTaskForUpload = 0;
	}

    /**
    * source device is always mobile device in our simulation scenarios!
    */
	@Override
	public double getUploadDelay(int sourceDeviceId, int destDeviceId, Task task) {
		// Decision tree:
		//   MAN relay (edge->edge placeholder id pair)
		//   Mobile -> Cloud (WAN)
		//   Mobile -> Edge (WLAN)
		double delay = 0;
		
		//special case for man communication
		if(sourceDeviceId == destDeviceId && sourceDeviceId == SimSettings.GENERIC_EDGE_DEVICE_ID){
			return delay = getManUploadDelay();
		}
		
		Location accessPointLocation = SimManager.getInstance().getMobilityModel().getLocation(sourceDeviceId,CloudSim.clock());

		//mobile device to cloud server
		if(destDeviceId == SimSettings.CLOUD_DATACENTER_ID){
			delay = getWanUploadDelay(accessPointLocation, task.getCloudletFileSize());
		}
		//mobile device to edge device (wifi access point)
		else if (destDeviceId == SimSettings.GENERIC_EDGE_DEVICE_ID) {
			delay = getWlanUploadDelay(accessPointLocation, task.getCloudletFileSize());
		}
		
		return delay;
	}

    /**
    * destination device is always mobile device in our simulation scenarios!
    */
	@Override
	public double getDownloadDelay(int sourceDeviceId, int destDeviceId, Task task) {
		// Decision tree mirrors upload:
		//   MAN relay
		//   Cloud -> Mobile (WAN)
		//   Edge -> Mobile (WLAN)
		double delay = 0;
		
		//special case for man communication
		if(sourceDeviceId == destDeviceId && sourceDeviceId == SimSettings.GENERIC_EDGE_DEVICE_ID){
			return delay = getManDownloadDelay();
		}
		
		Location accessPointLocation = SimManager.getInstance().getMobilityModel().getLocation(destDeviceId,CloudSim.clock());
		
		//cloud server to mobile device
		if(sourceDeviceId == SimSettings.CLOUD_DATACENTER_ID){
			delay = getWanDownloadDelay(accessPointLocation, task.getCloudletOutputSize());
		}
		//edge device (wifi access point) to mobile device
		else{
			delay = getWlanDownloadDelay(accessPointLocation, task.getCloudletOutputSize());
		}
		
		return delay;
	}

	@Override
	public void uploadStarted(Location accessPointLocation, int destDeviceId) {
		// Increment contention counters; must have matching uploadFinished for integrity
		if(destDeviceId == SimSettings.CLOUD_DATACENTER_ID)
			wanClients[accessPointLocation.getServingWlanId()]++;
		else if (destDeviceId == SimSettings.GENERIC_EDGE_DEVICE_ID)
			wlanClients[accessPointLocation.getServingWlanId()]++;
		else if (destDeviceId == SimSettings.GENERIC_EDGE_DEVICE_ID+1)
			manClients++;
		else {
			SimLogger.printLine("Error - unknown device id in uploadStarted(). Terminating simulation...");
			System.exit(0);
		}
	}

	@Override
	public void uploadFinished(Location accessPointLocation, int destDeviceId) {
		// Decrement counters; negative values would signal a logic bug
		if(destDeviceId == SimSettings.CLOUD_DATACENTER_ID)
			wanClients[accessPointLocation.getServingWlanId()]--;
		else if (destDeviceId == SimSettings.GENERIC_EDGE_DEVICE_ID)
			wlanClients[accessPointLocation.getServingWlanId()]--;
		else if (destDeviceId == SimSettings.GENERIC_EDGE_DEVICE_ID+1)
			manClients--;
		else {
			SimLogger.printLine("Error - unknown device id in uploadFinished(). Terminating simulation...");
			System.exit(0);
		}
	}

	@Override
	public void downloadStarted(Location accessPointLocation, int sourceDeviceId) {
		// Mirror uploadStarted: increment contention counters based on source (server side)
		if(sourceDeviceId == SimSettings.CLOUD_DATACENTER_ID)
			wanClients[accessPointLocation.getServingWlanId()]++;
		else if (sourceDeviceId == SimSettings.GENERIC_EDGE_DEVICE_ID)
			wlanClients[accessPointLocation.getServingWlanId()]++;
		else if (sourceDeviceId == SimSettings.GENERIC_EDGE_DEVICE_ID+1)
			manClients++;
		else {
			SimLogger.printLine("Error - unknown device id in downloadStarted(). Terminating simulation...");
			System.exit(0);
		}
	}

	@Override
	public void downloadFinished(Location accessPointLocation, int sourceDeviceId) {
		// Mirror uploadFinished: decrement contention counters
		if(sourceDeviceId == SimSettings.CLOUD_DATACENTER_ID)
			wanClients[accessPointLocation.getServingWlanId()]--;
		else if (sourceDeviceId == SimSettings.GENERIC_EDGE_DEVICE_ID)
			wlanClients[accessPointLocation.getServingWlanId()]--;
		else if (sourceDeviceId == SimSettings.GENERIC_EDGE_DEVICE_ID+1)
			manClients--;
		else {
			SimLogger.printLine("Error - unknown device id in downloadFinished(). Terminating simulation...");
			System.exit(0);
		}
	}

	private double getWlanDownloadDelay(Location accessPointLocation, double dataSize) {
		// Convert KB -> Kb and divide by empirical throughput (scaled by 3 for 802.11ac approximation)
		// Returns 0 if user index exceeds table => treated as bandwidth failure upstream
		int numOfWlanUser = wlanClients[accessPointLocation.getServingWlanId()];
		double taskSizeInKb = dataSize * (double)8; //KB to Kb
		double result=0;
		
		if(numOfWlanUser < experimentalWlanDelay.length)
			result = taskSizeInKb /*Kb*/ / (experimentalWlanDelay[numOfWlanUser]) /*Kbps*/; // Multiplying 3 is not necessary since experimentalWlanDelay already contains high-speed data

		//System.out.println("--> " + numOfWlanUser + " user, " + taskSizeInKb + " KB, " +result + " sec");
		return result;
	}
	
	private double getWlanUploadDelay(Location accessPointLocation, double dataSize) {
		// Symmetric with download
		return getWlanDownloadDelay(accessPointLocation, dataSize);
	}
	
	private double getWanDownloadDelay(Location accessPointLocation, double dataSize) {
		// WAN throughput lookup (no scaling factor)
		int numOfWanUser = wanClients[accessPointLocation.getServingWlanId()];
		double taskSizeInKb = dataSize * (double)8; //KB to Kb
		double result=0;
		
		if(numOfWanUser < experimentalWanDelay.length)
			result = taskSizeInKb /*Kb*/ / (experimentalWanDelay[numOfWanUser]) /*Kbps*/;
		
		//System.out.println("--> " + numOfWanUser + " user, " + taskSizeInKb + " KB, " +result + " sec");
		
		return result;
	}
	
	private double getWanUploadDelay(Location accessPointLocation, double dataSize) {
		// Symmetric with download
		return getWanDownloadDelay(accessPointLocation, dataSize);
	}
	
	private double calculateMM1(double propagationDelay, double bandwidth /*Kbps*/, double PoissonMean,
	                            double avgTaskSize /*KB*/, int deviceCount){
		// M/M/1 delay approximation:
		//   λ = 1/PoissonMean (tasks/s) * deviceCount (aggregate arrival rate)
		//   μ = bandwidth(Kbps) / avgTaskSize(Kb)
		//   Expected system time = 1 / (μ - λ)
		// Add propagation delay then cap excessive (>15s) delays to 0 to indicate failure.
		// Negative (μ <= λ) => saturated -> return 0 (failure)
		double mu=0, lamda=0;
		
		avgTaskSize = avgTaskSize * 8; //convert from KB to Kb

        lamda = ((double)1/(double)PoissonMean); //task per seconds
		mu = bandwidth /*Kbps*/ / avgTaskSize /*Kb*/; //task per seconds
		double result = (double)1 / (mu-lamda*(double)deviceCount);
		
		if(result < 0)
			return 0;
		
		result += propagationDelay;
		
		return (result > 15) ? 0 : result;
	}
	
	private double getManDownloadDelay() {
		// Use adaptive parameters to compute current MAN download delay
		// Update rolling statistics for next interval adaptation
		double result = calculateMM1(SimSettings.getInstance().getInternalLanDelay(),
				MAN_BW,
				ManPoissonMeanForDownload,
				avgManTaskOutputSize,
				numberOfMobileDevices);
		
		totalManTaskOutputSize += avgManTaskOutputSize;
		numOfManTaskForDownload++;
		
		//System.out.println("--> " + SimManager.getInstance().getNumOfMobileDevice() + " user, " +result + " sec");
		
		return result;
	}
	
	private double getManUploadDelay() {
		// Symmetric logic for MAN upload path
		double result = calculateMM1(SimSettings.getInstance().getInternalLanDelay(),
				MAN_BW,
				ManPoissonMeanForUpload,
				avgManTaskInputSize,
				numberOfMobileDevices);
		
		totalManTaskInputSize += avgManTaskInputSize;
		numOfManTaskForUpload++;

		//System.out.println(CloudSim.clock() + " -> " + SimManager.getInstance().getNumOfMobileDevice() + " user, " + result + " sec");
		
		return result;
	}
	
	public void updateMM1QueeuModel(){
		// Recompute Poisson means and average sizes based on window statistics.
		// Avoid division by zero by checking task counts.
		// Reset window accumulators for next period.
		double lastInterval = CloudSim.clock() - lastMM1QueueUpdateTime;
		lastMM1QueueUpdateTime = CloudSim.clock();
		
		if(numOfManTaskForDownload != 0){
			ManPoissonMeanForDownload = lastInterval / (numOfManTaskForDownload / (double)numberOfMobileDevices);
			avgManTaskOutputSize = totalManTaskOutputSize / numOfManTaskForDownload;
		}
		if(numOfManTaskForUpload != 0){
			ManPoissonMeanForUpload = lastInterval / (numOfManTaskForUpload / (double)numberOfMobileDevices);
			avgManTaskInputSize = totalManTaskInputSize / numOfManTaskForUpload;
		}
		
		totalManTaskOutputSize = 0;
		numOfManTaskForDownload = 0;
		totalManTaskInputSize = 0;
		numOfManTaskForUpload = 0;
	}
}
