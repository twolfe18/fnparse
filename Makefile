
SHELL := /bin/bash
cp="target/fnparse-1.0.0.jar:target/fnparse-1.0.0-jar-with-dependencies.jar:lib/*"

jar:
	mvn package assembly:assembly -DskipTests
	# upload jars because i can't yet compile on the COE...
	#scp target/*.jar coe:~/fnparse/target

all:
	#-agentlib:hprof=cpu=samples,interval=100,depth=10,file=parser_exp.hprof,thread=y
	time java -ea -Xmx8G -cp $(cp) edu.jhu.hlt.fnparse.experiment.ParserExperiment \
		2>&1 | tee experiments/targetId/ParserExperiment.mba.log

frameIdQsub:
	qsub -q text.q -t 1-192 frame-id-experiment.qsub && \
		sleep 2 && qinfo

frameIdPerf.txt:
	for f in logging/targetId/*; do \
		p=`grep "test" $$f | grep TargetMicroF1 | tail -n 1 | awk '{print $$NF}'` && \
		c=`grep -m 1 "config =" $$f` && \
		echo "$$p $$c"; \
	done | tee frameIdPerf.txt


