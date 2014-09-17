
jar:
	mvn package assembly:assembly -DskipTests

frameIdTrain:
	qsub -N frameId-regular -t 1-36 -q text.q ParserExperimentWrapper.qsub frameId regular
	qsub -N frameId-latent -t 1-36 -q text.q ParserExperimentWrapper.qsub frameId latent
	qsub -N frameId-none -t 1-36 -q text.q ParserExperimentWrapper.qsub frameId none

argIdTrain:
	qsub -N argId-regular -t 1-36 -q text.q ParserExperimentWrapper.qsub argId regular
	qsub -N argId-latent -t 1-36 -q text.q ParserExperimentWrapper.qsub argId latent
	qsub -N argId-none -t 1-36 -q text.q ParserExperimentWrapper.qsub argId none

argSpansTrain:
	qsub -N argSpans-regular -t 1-36 -q text.q ParserExperimentWrapper.qsub argSpans regular
	qsub -N argSpans-latent -t 1-36 -q text.q ParserExperimentWrapper.qsub argSpans latent
	qsub -N argSpans-none -t 1-36 -q text.q ParserExperimentWrapper.qsub argSpans none

evaluate:
	@echo "implement me!"

