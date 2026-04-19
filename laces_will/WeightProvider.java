package edu.boun.edgecloudsim.applications.laces;

public interface WeightProvider {
    double[] getWeights(double[] context);
    void reportReward(double reward);

    class Java {
        public String[] implementsInterfaces() {
            return new String[] {
                "edu.boun.edgecloudsim.applications.laces.WeightProvider"
            };
        }
    }
}