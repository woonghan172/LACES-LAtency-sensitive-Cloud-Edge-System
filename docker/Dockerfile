FROM ubuntu:22.04

# Set timezone to Minnesota (Central Time)
ENV TZ=America/Chicago
RUN apt-get update && apt-get install -y \
    tzdata \
    openjdk-21-jdk \
    && ln -snf /usr/share/zoneinfo/$TZ /etc/localtime \
    && echo $TZ > /etc/timezone \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

# Copy EdgeCloudSim project
COPY EdgeCloudSim/ ./EdgeCloudSim/

# Grant execute permission to all shell scripts
RUN find /workspace/EdgeCloudSim/scripts -name "*.sh" -exec chmod +x {} \;

# Switch to the laces script directory (all relative paths are based on this)
WORKDIR /workspace/EdgeCloudSim/scripts/laces

# Compile Java source code
RUN ./compile.sh

# Default: 4 parallel processes, 1 iteration
# Override example: docker run <image> ./run_scenarios.sh 2 5
CMD ["./run_scenarios.sh", "4", "1"]
