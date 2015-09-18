#$ -cwd
#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=4G
#$ -l num_proc=1
#$ -S /bin/bash

# Given some features and a bialph, write a new copy of feature file where we
# use the oldInt -> newInt mapping to re-write the given feautres.
# Wrapper around edu.jhu.hlt.fnparse.features.precompute.BiAlphProjection.

echo "starting at `date` on $HOSTNAME"
echo "args: $@"

set -e

if [[ $# != 5 ]]; then
  echo "please provide:"
  echo "1) input features"
  echo "2) input bialph"
  echo "3) output features"
  echo "4) a jar file"
  echo "5) whether to remove the input bialph (Y|N)"
  exit 1
fi

java -Xmx3G -ea -server -cp $4 \
  -DinputFeatures=$1 \
  -DinputBialph=$2 \
  -DoutputFeatures=$3 \
  edu.jhu.hlt.fnparse.features.precompute.BiAlphProjection

echo "ret code: $?"

if [[ $5 == "Y" ]]; then
  echo "removing $2"
  rm $2
fi

echo "done at `date`"

