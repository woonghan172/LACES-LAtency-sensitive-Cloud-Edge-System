package edu.boun.edgecloudsim.applications.laces_will;

public interface WeightProvider {
    double[] getWeights(double[] context);
    void reportReward(double reward);

    class Java {
        public String[] implementsInterfaces() {
            return new String[] {
                "edu.boun.edgecloudsim.applications.laces_will.WeightProvider"
            };
        }
    }
}