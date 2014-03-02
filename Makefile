
cp="target/fnparse-1.0.0.jar:target/fnparse-1.0.0-jar-with-dependencies.jar:lib/*"

jar:
	mvn package assembly:assembly -DskipTests

all:
	#-agentlib:hprof=cpu=samples,interval=100,depth=10,file=parser_exp.hprof,thread=y
	time java -ea -Xmx8G -cp $(cp) edu.jhu.hlt.fnparse.experiment.ParserExperiment \
		2>&1 | tee experiments/targetId/ParserExperiment.mba.log

frameIdQsub:
	qsub -q text.q -t 1-420 frame-id-experiment.qsub && \
		sleep 2 && qinfo

