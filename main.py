import time

class CoreCloud:
    def __init__(self):
        self.storage = []

    def heavy_processing(self, data):
        # print("[Core Cloud] Performing deep analytics and long-term storage...")
        time.sleep(1)  # Simulating high latency/intense CPU work
        processed_result = f"Analyzed: {data.upper()}"
        self.storage.append(processed_result)
        return processed_result

class EdgeCloud:
    def __init__(self, core_cloud):
        self.core = core_cloud
        self.cache = {}

    def process_request(self, data):
        # print(f"[Edge Cloud] Received: '{data}'. Checking local cache...")
        
        # Simple Edge Logic: If we've seen it, return fast. 
        # Otherwise, escalate to Core.
        if data in self.cache:
            return f"Cached Result: {self.cache[data]}"
        
        # print("[Edge Cloud] Task too complex or not in cache. Sending to Core...")
        result = self.core.heavy_processing(data)
        self.cache[data] = result
        return result

class UserDevice:
    def __init__(self, edge_cloud):
        self.edge = edge_cloud
        self.latencies = [] # record latencies for analysis

    def capture_and_send(self, sensor_data):
        # print(f"\n[User Device] Captured sensor data: {sensor_data}")
        # Basic pre-filtering (don't send empty data)
        if not sensor_data:
            return "Error: No data"
        
        start = time.perf_counter()

        result = self.edge.process_request(sensor_data)

        end = time.perf_counter()
        
        latency = end - start
        self.latencies.append(latency)
        # print(f"[Metrics] End-to-end latency: {latency:.4f} sec")
            
        return result
    
if __name__ == "__main__":
    core = CoreCloud()
    edge = EdgeCloud(core)
    user = UserDevice(edge)