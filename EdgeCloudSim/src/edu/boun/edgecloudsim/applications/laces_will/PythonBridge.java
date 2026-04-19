package edu.boun.edgecloudsim.applications.laces_will;

public class PythonBridge {
    private volatile WeightProvider weightProvider;

    public void registerWeightProvider(WeightProvider provider) {
        this.weightProvider = provider;
        System.out.println("Python WeightProvider registered.");
    }

    public WeightProvider getWeightProvider() {
        return this.weightProvider;
    }
}