#! /bin/sh
java -Xmx3096M -cp target:lib/ECLA.jar:lib/DTNConsoleConnection.jar:lib/jxl.jar core.DTNSim $*
