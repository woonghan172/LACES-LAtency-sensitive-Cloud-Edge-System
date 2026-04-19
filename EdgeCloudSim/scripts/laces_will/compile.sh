#!/bin/sh
rm -rf ../../bin
mkdir ../../bin
if [ ! -f ../../lib/py4j.jar ]; then
  echo "Missing ../../lib/py4j.jar. Add the Py4J Java jar before compiling laces_will." >&2
  exit 1
fi
javac -classpath "../../lib/cloudsim-7.0.0-alpha.jar:../../lib/commons-math3-3.6.1.jar:../../lib/colt.jar:../../lib/py4j.jar" -sourcepath ../../src ../../src/edu/boun/edgecloudsim/applications/laces_will/LacesMainApp.java -d ../../bin
