#!/bin/sh

mvn clean install -D maven.test.skip=true

cd target

echo Starting command line monitor:

java -cp lib/*:./* org.cattech.WMR88Interface.CommandLineMonitor
