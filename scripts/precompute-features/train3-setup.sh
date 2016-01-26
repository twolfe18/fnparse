
# Point of this script is to call ShimMode's main method
# which produces a java serialized version of the data with all
# the features computed and products computed.
# This is the last step before running ShimModel in earnest to
# get real results.

set -eu

if [[ $# != 1 ]]; then
  echo "please provide a dataset name, i.e. either \"propbank\" or \"framenet\""
  exit 1
fi

#DATASET=propbank
DATASET=$1

if [[ $DATASET == "propbank" ]]; then
  PROPBANK=true
elif [[ $DATASET == "framenet" ]]; then
  PROPBANK=false
else
  echo "unknown dataset: $DATASET"
  exit 2
fi

DATA_HOME=/export/projects/twolfe/fnparse-data
DD=/export/projects/twolfe/fnparse-output/experiments/dec-experiments/$DATASET
CD=$DD/coherent-shards-filtered-small-jser

if [[ -d $CD ]]; then
  echo "already exists, did you already run?"
  echo $CD
  exit 3
fi

mkdir -p $CD/sge-logs

echo "copying features to a safe place..."
mkdir -p $CD/features
for f in scripts/having-a-laugh/$DATASET-*; do
  echo "$f  ==>  $CD/features"
  cp $f $CD/features
done

echo "copying jar to a safe place..."
JAR=target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar
JAR_STABLE=$CD/fnparse.jar
echo "    $JAR"
echo "==> $JAR_STABLE"
cp $JAR $JAR_STABLE

for C in 4 8 16; do
for D in 320 640 1280; do

if [[ ! -f $CD/features/$DATASET-$C-${D}.fs ]]; then
  echo "cant find features: $CD/features/$DATASET-$C-${D}.fs"
else
K="C$C-D$D"
mkdir $CD/$K
qsub -N "fc-$K" \
  -b y -j y -V -o $CD/sge-logs \
  -l 'mem_free=48G,num_proc=1,h_rt=24:00:00' \
  java -Xmx46G -XX:+UseSerialGC -server -cp $CD/fnparse.jar \
    edu.jhu.hlt.fnparse.rl.rerank.ShimModel \
    dontTrain true \
    propbank $PROPBANK \
    primesFile ${DATA_HOME}/primes/primes1.byLine.txt.gz \
    framenet.dipanjan.splits /export/projects/twolfe/fnparse-data/development-split.dipanjan-train.txt \
    data.framenet.root ${DATA_HOME} \
    data.wordnet ${DATA_HOME}/wordnet/dict \
    data.embeddings ${DATA_HOME}/embeddings \
    data.ontonotes5 /export/common/data/corpora/LDC/LDC2013T19/data/files/data/english/annotations \
    data.propbank.conll ${DATA_HOME}/conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data \
    data.propbank.frames ${DATA_HOME}/ontonotes-release-5.0-fixed-frames/frames \
    bialph $DD/coherent-shards-filtered-small/alphabet.txt \
    featuresParent $DD/coherent-shards-filtered-small/features \
    featureSet $CD/features/$DATASET-$C-${D}.fs \
    trainData $CD/$K/train.jser.gz \
    devData $CD/$K/dev.jser.gz \
    testData $CD/$K/test.jser.gz
fi

done
done

