#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=8G
#$ -l num_proc=1
#$ -S /bin/bash

echo "starting at `date` on $HOSTNAME in `pwd`"
echo "args: $@"

#JAR=target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar
JAR=/export/projects/twolfe/fnparse-output/experiments/debug/jan13b/fnparse.jar
if [[ ! -f $JAR ]]; then
  echo "put the jar here:"
  echo $JAR
  exit 1
fi

DD=/export/projects/twolfe/fnparse-output/experiments/dec-experiments/propbank
DATA_HOME=/export/projects/twolfe/fnparse-data

java -cp $JAR -ea -server -Xmx6G -XX:+UseSerialGC \
  edu.jhu.hlt.fnparse.rl.full.FModel \
  fooFeatureFile $DD/coherent-shards-filtered-small/features/shard250.txt \
  bialph $DD/coherent-shards-filtered-small/alphabet.txt \
  featureSetParent /home/hltcoe/twolfe/fnparse-build/fnparse/scripts/having-a-laugh \
  propbank true \
  data.framenet.root ${DATA_HOME} \
  data.wordnet ${DATA_HOME}/wordnet/dict \
  data.embeddings ${DATA_HOME}/embeddings \
  data.ontonotes5 /export/common/data/corpora/LDC/LDC2013T19/data/files/data/english/annotations \
  data.propbank.conll ${DATA_HOME}/conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data \
  data.propbank.frames ${DATA_HOME}/ontonotes-release-5.0-fixed-frames/frames \
  primesFile ${DATA_HOME}/primes/primes1.byLine.txt.gz \
  $@

echo "ret code: $?"
echo "done at `date`"

