
cp="target/fnparse-1.0.0.jar:target/fnparse-1.0.0-jar-with-dependencies.jar:lib/*"
gc="-XX:ParallelGCThreads=2"
memProf="-agentlib:hprof=heap=sites,interval=5000"

frameIdMem="-Xmx7G"
argIdMem="-Xmx14G"
argIdAlphTimeout="60"	# in minutes


#############################################################################################################


jar:
	mvn package assembly:assembly -DskipTests


#############################################################################################################


# count features for frame id
frameIdSetup:
	#jar
	time java -ea $(frameIdMem) $(gc) -cp $(cp) edu.jhu.hlt.fnparse.experiment.AlphabetComputer \
		saved-models/alphabets/frameId-reg.model.gz frameId regular \
		2>&1 | tee saved-models/alphabets/frameId-reg.log
	time java -ea $(frameIdMem) $(gc) -cp $(cp) edu.jhu.hlt.fnparse.experiment.AlphabetComputer \
		saved-models/alphabets/frameId-latent.model.gz frameId latent \
		2>&1 | tee saved-models/alphabets/frameId-latent.log


# just train one model, no parmeter sweep
frameIdTrainOne:
	mkdir -p saved-models/temp
	time java -ea $(frameIdMem) $(gc) -cp $(cp) edu.jhu.hlt.fnparse.experiment.ParserExperiment \
		frameId 14 saved-models/temp saved-models/alphabets/frameId-reg.model.gz regular \
		2>&1 | tee saved-models/full/frameId-reg.log
	mv -f saved-models/temp/FRAME_ID.model.gz saved-models/full/frameId-reg.model.gz
	#time java -ea $(frameIdMem) $(gc) -cp $(cp) edu.jhu.hlt.fnparse.experiment.ParserExperiment \
		frameId 44 saved-models/temp saved-models/alphabets/frameId-latent.model.gz latent \
		2>&1 | tee saved-models/full/frameId-latent.log
	#mv -f saved-models/temp/FRAME_ID.model.gz saved-models/full/frameId-latent.model.gz


# launch all the frame id training jobs to the grid
frameIdTrainJob:
	#frameIdSetup jar
	qsub -q text.q -t 1-36 \
		./ParserExperimentWrapper.qsub frameId saved-models/alphabets/frameId-reg.model.gz regular
	qsub -q text.q -t 1-36 \
		./ParserExperimentWrapper.qsub frameId saved-models/alphabets/frameId-reg.model.gz none
	qsub -q text.q -t 1-36 \
		./ParserExperimentWrapper.qsub frameId saved-models/alphabets/frameId-latent.model.gz latent


# Added Sept. 8, lowered ambitions
frameIdTrainNew: jar
	qsub -N frameId-latent-simpleBinary -q text.q ParserExperimentWrapper.qsub frameId none latent


#############################################################################################################


argIdSetupRegular:
	#jar
	time java -ea $(argIdMem) $(gc) -cp $(cp) edu.jhu.hlt.fnparse.experiment.AlphabetComputer \
		saved-models/alphabets/argId-reg.model.gz argId regular \
		saved-models/full/frameId-reg.model.gz $(argIdAlphTimeout) \
		2>&1 | tee saved-models/alphabets/argId-reg.log

argIdSetupLatent:
	#jar
	time java -ea $(argIdMem) -cp $(cp) edu.jhu.hlt.fnparse.experiment.AlphabetComputer \
		saved-models/alphabets/argId-latent.model.gz argId latent \
		saved-models/full/frameId-latent.model.gz $(argIdAlphTimeout) \
		2>&1 | tee saved-models/alphabets/argId-latent.log


# just train one model, no parmeter sweep
argIdTrainOne:
	mkdir -p saved-models/temp
	time java -ea $(argIdMem) $(gc) -cp $(cp) edu.jhu.hlt.fnparse.experiment.ParserExperiment \
		argId 4 saved-models/temp saved-models/alphabets/argId-reg.model.gz regular \
		2>&1 | tee saved-models/full/argId-reg.log
	mv -f saved-models/temp/PIPELINE_FRAME_ARG/PIPELINE_FRAME_ARG.model.gz saved-models/full/argId-reg.model.gz
	sort -n saved-models/temp/PIPELINE_FRAME_ARG/PIPELINE_FRAME_ARG.weights.txt >saved-models/full/argId.weights.txt


argIdTrainJob:
	#argIdSetup jar
	qsub -q text.q -t 1-72 \
		./ParserExperimentWrapper.qsub argId saved-models/alphabets/argId-reg.model.gz regular
	qsub -q text.q -t 1-72 \
		./ParserExperimentWrapper.qsub argId saved-models/alphabets/argId-reg.model.gz none
	qsub -q text.q -t 1-72 \
		./ParserExperimentWrapper.qsub argId saved-models/alphabets/argId-latent.model.gz latent
	


