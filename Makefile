
cp="target/fnparse-1.0.0.jar:target/fnparse-1.0.0-jar-with-dependencies.jar"

jar:
	mvn package assembly:assembly -DskipTests

all: jar
	#-agentlib:hprof=cpu=samples,interval=100,depth=10,file=parser_exp.hprof,thread=y
	time java -ea -Xmx8G -cp $(cp) edu.jhu.hlt.fnparse.experiment.ParserExperiment \
		2>&1 | tee experiments/targetId/ParserExperiment.mba.log

