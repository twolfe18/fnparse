#!/bin/bash
set -eu

# Launch one job for every entity to do feature extraction

PARENT=$1
OBS_ARG_TYPES=$2
BINARY_LABELS=$3
JAR=$4

if [[ `echo $PARENT | grep -cP '/train/?$'` == 1 ]]; then
TRAIN="true"
else
TRAIN="false"
fi
echo "TRAIN=$TRAIN"

DF=/export/projects/twolfe/sit-search/idf/cms/df-cms-simpleaccumulo-twolfe-cag1-nhash12-logb20.jser

for ML in `find $PARENT -name 'mentionLocs.txt'`; do
	ENTITY_DIR=`dirname $ML`
	echo "$ML $ENTITY_DIR"
	qsub -cwd -N distsup-featex -b y -j y -o $ENTITY_DIR -l "num_proc=1,mem_free=3G,h_rt=1:00:00" \
		java -ea -server -Xmx3G -cp $JAR edu.jhu.hlt.entsum.SlotsAsConcepts \
			mode extractFeaturesForOneEntity \
			obsArgTypes $OBS_ARG_TYPES \
			entityDir $ENTITY_DIR \
			train $TRAIN \
			binary $BINARY_LABELS \
			wordDocFreq $DF
done

