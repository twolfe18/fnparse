
jar:
	mvn package assembly:assembly -DskipTests

frameIdTrain:
	qsub -N frameId-latent -t 1-36 -q text.q ParserExperimentWrapper.qsub frameId latent
	qsub -N frameId-regular -t 1-36 -q text.q ParserExperimentWrapper.qsub frameId regular
	qsub -N frameId-none -t 1-36 -q text.q ParserExperimentWrapper.qsub frameId none

evaluate:
	@echo "implement me!"

