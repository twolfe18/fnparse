
cp="target/fnparse-1.0.0.jar:target/fnparse-1.0.0-jar-with-dependencies.jar"

all:
	#mvn assembly:assembly -DskipTests && \
	mvn package -DskipTests &&
	java -ea -Xmx2G -agentlib:hprof=cpu=samples,interval=100,depth=10,file=parser_exp.hprof,thread=y -cp $(cp) edu.jhu.hlt.fnparse.experiment.ParserExperiment

