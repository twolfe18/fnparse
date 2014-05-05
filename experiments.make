
cp="target/fnparse-1.0.0.jar:target/fnparse-1.0.0-jar-with-dependencies.jar:lib/*"
memProf="-agentlib:hprof=heap=sites,interval=5000"

jar:
	mvn package assembly:assembly -DskipTests


# count features for frame id
frameIdSetup:
	#jar
	time java -ea -Xmx6G -cp $(cp) edu.jhu.hlt.fnparse.experiment.AlphabetComputer \
		saved-models/alphabets/frameId-reg.model.gz frameId regular \
		2>&1 | tee saved-models/alphabets/frameId-reg.log
	# TODO have one for latentSyntax and noSyntax
	#echo "frame id was set up on `date`" >frameIdSetup
	#echo "delete this file to force this step to be re-run" >>frameIdSetup



# just train one model, no parmeter sweep
frameIdTrainOne:
	mkdir -p saved-models/temp
	time java -ea -Xmx9G -cp $(cp) edu.jhu.hlt.fnparse.experiment.ParserExperiment \
		frameId 189 saved-models/temp saved-models/alphabets/frameId-reg.model.gz \
		2>&1 | tee saved-models/full/frameId.log
	mv -f saved-models/temp/FRAME_ID/FRAME_ID.model.gz saved-models/full/frameId-reg.model.gz
	rm -rf saved-models/temp


# launch all the frame id training jobs to the grid
frameIdTrainJob:
	#frameIdSetup jar
	n=`java -cp target/classes/ edu.jhu.hlt.fnparse.experiment.ParserExperiment 2>&1 | grep 'please request' | awk '{print $3}'`
	#qsub -q text.q -t 1-240 frame-id-experiment.qsub && \
	#	sleep 2 && qinfo
	echo $n
	#echo "frame models were trained on `date`" >frameIdTrainJob


# TODO make this depend on frameIdTrainJob and link up to best model
argIdSetup:
	#jar
	time java -ea -Xmx9G -cp $(cp) edu.jhu.hlt.fnparse.experiment.AlphabetComputer \
		saved-models/alphabets/argId-reg.model.gz argId regular \
		saved-models/full/frameId-reg.model.gz \
		2>&1 | tee saved-models/alphabets/argId-reg.log
	#echo "arg id setup was run on `date`" >argIdSetup && \
	#echo "delete this file to force this step to be re-run" >>argIdSetup

# just train one model, no parmeter sweep
argIdTrainOne:
	mkdir -p saved-models/temp
	time java -ea -Xmx9G -cp $(cp) edu.jhu.hlt.fnparse.experiment.ParserExperiment \
		argId 12 saved-models/temp saved-models/alphabets/argId-reg.model.gz \
		2>&1 | tee saved-models/full/argId-reg.log
	mv -f saved-models/temp/PIPELINE_FRAME_ARG/PIPELINE_FRAME_ARG.model.gz saved-models/full/argId-reg.model.gz
	rm -rf saved-models/temp


# TODO fixme
argIdTrainJob:
	#argIdSetup jar
	time java -ea -Xmx9G -cp $(cp) edu.jhu.hlt.fnparse.experiment.ParserExperiment 12
	exit -1
	


