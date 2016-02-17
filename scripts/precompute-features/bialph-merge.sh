#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=2G
#$ -l num_proc=1
#$ -S /bin/bash

# Given two bialphs, call edu.jhu.hlt.fnparse.features.precompute.AlphabetMerger

echo "starting at `date` on $HOSTNAME"
echo "args: $@"

set -e

if [[ $# != 5 ]]; then
  echo "please provide:"
  echo "1) input bialph 1"
  echo "2) input bialph 2"
  echo "3) output bialph 1"
  echo "4) output bialph 2"
  echo "5) a jar file"
  exit 1
fi

java -Xmx1G -ea -server -cp $5 \
  -DinAlph1=$1 \
  -DinAlph2=$2 \
  -DoutAlph1=$3 \
  -DoutAlph2=$4 \
  edu.jhu.hlt.fnparse.features.precompute.BiAlphMerger

# NOTE: You might think that you can remove the input files once you are done
# here, but the input file may actually be used in more than once place when
# there is a non-power-of-two number of items to merge.
# DON'T remove the inputs!

echo "ret code: $?"

K=20
echo "sleeping for $K seconds to ensure that dependent jobs don't see stale NFS data..."
sleep $K

echo "done at `date`"

