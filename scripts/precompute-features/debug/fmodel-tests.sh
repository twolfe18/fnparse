#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=5G
#$ -l num_proc=1
#$ -S /bin/bash

echo "starting at `date` on $HOSTNAME in `pwd`"
echo "args: $@"

#JAR=target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar
JAR=/export/projects/twolfe/fnparse-output/experiments/debug/jan6a/fnparse.jar
if [[ ! -f $JAR ]]; then
  echo "put the jar here:"
  echo $JAR
  exit 1
fi

DD=/export/projects/twolfe/fnparse-output/experiments/dec-experiments/propbank

java -cp $JAR -ea -server -Xmx4G edu.jhu.hlt.fnparse.rl.full.FModel \
  fooFeatureFile $DD/coherent-shards-filtered-small/features/shard250.txt \
  bialph $DD/coherent-shards-filtered-small/alphabet.txt \
  featureSetParent /home/hltcoe/twolfe/fnparse/scripts/having-a-laugh \
  learningRate 0.1 \
  maxIters 30 \
  $@

echo "ret code: $?"
echo "done at `date`"

