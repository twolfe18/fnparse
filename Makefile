
cp="target/fnparse-1.0.0.jar:target/fnparse-1.0.0-jar-with-dependencies.jar"

all:
	#-agentlib:hprof=cpu=samples,interval=100,depth=10,file=parser_exp.hprof,thread=y
	mvn package assembly:assembly -DskipTests && \
	time java -ea -Xmx2G -cp $(cp) edu.jhu.hlt.fnparse.experiment.ParserExperiment \
	2>&1 | tee experiments/targetId/ParserExperiment.mba.log

