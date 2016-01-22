
# - convert pipeline over to something other than RerankerTrainer which is fubar
#   - does the timing work? (for IO in particular)
#     read all of propbank via PropbankReader: 72 seconds
#     real all the features for one shard: 21 seoncds (261M, 24k lines, md5=fda87e019773379796f74461ea08dbe2)
#     read bialph: 3 seconds (1.8G, 17M lines, md5=a7ba15f707a1351dee2ced7f8ee8bc48)
#     compute feature products for one shard: 16 seconds (8-1280 feature set)
# 
#     (21 + 16) * 256 / 3600.0 = 2.6 hours
#     => This is totally reasonable if you know its working, no threading needed
# 
#   - can I afford to do prediction on the dev and test set every epoch?
#     => if not, just make a smaller version which you can so you know roughly how things are going -- should be easy to parse 300 sentences to get a good idea



# Point of this script is to call ShimMode's main method
# which produces a java serialized version of the data with all
# the features computed and products computed.
# This is the last step before running ShimModel in earnest to
# get real results.

DATASET=propbank
PROPBANK=true
DATA_HOME=/export/projects/twolfe/fnparse-data
DD=/export/projects/twolfe/fnparse-output/experiments/dec-experiments/$DATASET
CD=$DD/coherent-shards-filtered-small-jser
mkdir -p $CD/sge-logs

echo "copying features to a safe place..."
mkdir -p $CD/features
for f in scripts/having-a-laugh/propbank-*; do
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

