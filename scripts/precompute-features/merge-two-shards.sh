#$ -cwd
#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=16G
#$ -l num_proc=1
#$ -S /bin/bash

# Just a wrapper around AlphabetMerger.

set -e

echo "arguments: $@"

if [[ $# != 4 ]]; then
  echo "please provide:"
  echo "1) input working directory A"
  echo "2) input working directory B"
  echo "3) output working directory"
  echo "4) a jar file in a stable location"
  echo "where a \"working directory\" is a directory made by a FeaturePrecomputation job"
  echo "which contains a file called features.txt.gz and template-feat-indices.txt.gz"
  exit 1
fi

JOB_DIR_A=$1
JOB_DIR_B=$2
OUTPUT_DIR=$3
JAR=$4

java -Xmx15G -ea -server -cp $JAR \
  -DfeatIn1=$JOB_DIR_A/features.txt.gz \
  -DalphIn1=$JOB_DIR_A/template-feat-indices.txt.gz \
  -DfeatIn2=$JOB_DIR_B/features.txt.gz \
  -DalphIn2=$JOB_DIR_B/template-feat-indices.txt.gz \
  -DfeatOut=$OUTPUT_DIR/features.txt.gz \
  -DalphOut=$OUTPUT_DIR/template-feat-indices.txt.gz \
  edu.jhu.hlt.fnparse.features.precompute.AlphabetMerger

