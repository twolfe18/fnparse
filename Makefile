
cp="target/fnparse-1.0.0.jar:target/fnparse-1.0.0-jar-with-dependencies.jar"

all:
	mvn package assembly:assembly -DskipTests && \
	time java -ea -Xmx3G -agentlib:hprof=cpu=samples,interval=100,depth=10,file=parser_exp.hprof,thread=y -cp $(cp) edu.jhu.hlt.fnparse.experiment.ParserExperiment \
	2>&1 | tee parser_experiment.log

