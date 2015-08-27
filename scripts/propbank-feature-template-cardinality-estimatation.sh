#!/bin/bash
#$ -cwd                         # run from current working directory
#$ -j y                         # join stderr to stdout
#$ -V                           # job has the same environment variables as the submission shell
#$ -l h_rt=72:00:00             # runtime limit
#$ -l mem_free=12G              # expected amount of mem
#$ -l num_proc=1               # how many processors to use
#$ -o logs/propbank-template-cardinality-estimation                       # where to do logging

# Expects to be run from the fnparse directory (parent of this file)

# Don't forget to start the redis server!
# The DB should be saved in data/cache/parses/

# Do some tests
#mvn compile exec:java \
#  -Dexec.mainClass=edu.jhu.hlt.fnparse.data.PropbankDataEndToEndTest \
#  -Ddata.wordnet=toydata/wordnet/dict \
#  -Ddata.embeddings=data/embeddings \
#  -Ddata.ontonotes5=data/ontonotes-release-5.0/LDC2013T19/data/files/data/english/annotations \
#  -Ddata.propbank.conll=../conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data \
#  -Ddata.propbank.frames=data/ontonotes-release-5.0-fixed-frames/frames \
#  -Dredis.host.propbankParses=localhost \
#  -Dredis.port.propbankParses=6379 \
#  -Dredis.db.propbankParses=0 \
#  2>&1 \
#  | tee /tmp/debug.txt \
#  || exit 2

set -e

# Add the dparses
#echo "edu.jhu.hlt.fnparse.data.propbank.ParsePropbankData"
#mvn exec:java \
#  -XX:+UseSerialGC \
#  -Dexec.mainClass=edu.jhu.hlt.fnparse.data.propbank.ParsePropbankData \
#  -Ddata.wordnet=toydata/wordnet/dict \
#  -Ddata.embeddings=data/embeddings \
#  -Ddata.ontonotes5=data/ontonotes-release-5.0/LDC2013T19/data/files/data/english/annotations \
#  -Ddata.propbank.conll=../conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data \
#  -Ddata.propbank.frames=data/ontonotes-release-5.0-fixed-frames/frames \
#  -Dredis.host.propbankParses=$1 \
#  -Dredis.port.propbankParses=6379 \
#  -Dredis.db.propbankParses=0 \
#  -DroleHeadStage.useArgPruner=false \
#  -DdisallowConcreteStanford=false \
#  -Dshard=$2 \
#  -DnumShards=$3 \
#  -DusePropbank=true \
#  2>&1 \
#  | tee /tmp/propbank-parse.log


# Compute the feature/template cardinalities
# Note: I have since updated tutils.ExperimentalProperties to allow for either -Dkey=value or main-args style arguments
mvn compile exec:java \
  -Dexec.mainClass=edu.jhu.hlt.fnparse.inference.frameid.BasicFeatureTemplates \
  -Ddata.wordnet=toydata/wordnet/dict \
  -Ddata.embeddings=data/embeddings \
  -Ddata.ontonotes5=data/ontonotes-release-5.0/LDC2013T19/data/files/data/english/annotations \
  -Ddata.propbank.conll=../conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data \
  -Ddata.propbank.frames=data/ontonotes-release-5.0-fixed-frames/frames \
  -Dredis.host.propbankParses=localhost \
  -Dredis.port.propbankParses=6379 \
  -Dredis.db.propbankParses=0 \
  -DroleHeadStage.useArgPruner=false \
  -DroleHeadStage.showFeatures=false \
  -DdisallowConcreteStanford=true \
  -Dexec.args="--output /tmp/card-est-propbank.txt --part 0 --numParts 5 --usePropbank true --onlyDoStage RoleHeadStage" \
  2>&1 \
  | tee /tmp/propbank-feature-template-cardinality-estimation.log



