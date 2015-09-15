#$ -cwd
#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=4G
#$ -l num_proc=1
#$ -S /bin/bash

set -e

if [[ $# != 3 ]]; then
  echo "please provide:"
  echo "1) a working directory"
  echo "2) an output file for (template, information-gain) pairs"
  echo "3) a jar file"
  echo "where a \"working directory\" is a directory made by a FeaturePrecomputation job"
  echo "which contains a file called features.txt.gz and template-feat-indices.txt.gz"
  exit 1
fi

WORKING_DIR=$1
OUTPUT=$2
JAR=$3

java -Xmx3G -ea -server -cp $JAR \
  -Dfeatures=$WORKING_DIR/features.txt.gz \
  -DtemplateAlph=$WORKING_DIR/template-feat-indices.txt.gz \
  -DtopK=30 \
  -DoutputFeats=$OUTPUT \
  edu.jhu.hlt.fnparse.features.precompute.InformationGain

