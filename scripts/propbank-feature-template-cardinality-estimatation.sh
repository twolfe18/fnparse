#!/bin/bash

# Expects to be run from the fnparse directory (parent of this file)

# Don't forget to start the redis server!
# The DB should be saved in data/cache/parses/

#echo "edu.jhu.hlt.fnparse.data.PropbankDataEndToEndTest"
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

echo "edu.jhu.hlt.fnparse.data.propbank.ParsePropbankData"
mvn compile exec:java \
  -Dexec.mainClass=edu.jhu.hlt.fnparse.data.propbank.ParsePropbankData \
  -Ddata.wordnet=toydata/wordnet/dict \
  -Ddata.embeddings=data/embeddings \
  -Ddata.ontonotes5=data/ontonotes-release-5.0/LDC2013T19/data/files/data/english/annotations \
  -Ddata.propbank.conll=../conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data \
  -Ddata.propbank.frames=data/ontonotes-release-5.0-fixed-frames/frames \
  -Dredis.host.propbankParses=localhost \
  -Dredis.port.propbankParses=6379 \
  -Dredis.db.propbankParses=0 \
  -DroleHeadStage.useArgPruner=false \
  -DdisallowConcreteStanford=false \
  -Dshard=0 \
  -DnumShards=5 \
  -DusePropbank=true \
  2>&1 \
  | tee /tmp/propbank-parse.log


# Note: I have since updated tutils.ExperimentalProperties to allow for either -Dkey=value or main-args style arguments
#echo "edu.jhu.hlt.fnparse.inference.frameid.BasicFeatureTemplates"
#mvn compile exec:java \
#  -Dexec.mainClass=edu.jhu.hlt.fnparse.inference.frameid.BasicFeatureTemplates \
#  -Ddata.wordnet=toydata/wordnet/dict \
#  -Ddata.embeddings=data/embeddings \
#  -Ddata.ontonotes5=data/ontonotes-release-5.0/LDC2013T19/data/files/data/english/annotations \
#  -Ddata.propbank.conll=../conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data \
#  -Ddata.propbank.frames=data/ontonotes-release-5.0-fixed-frames/frames \
#  -Dredis.host.propbankParses=localhost \
#  -Dredis.port.propbankParses=6379 \
#  -Dredis.db.propbankParses=0 \
#  -DroleHeadStage.useArgPruner=false \
#  -DdisallowConcreteStanford=true \
#  -Dexec.args="--output /tmp/card-est-propbank.txt --part 0 --numParts 200 --usePropbank true --onlyDoStage RoleHeadStage" \
#  2>&1 \
#  | tee /tmp/propbank-feature-template-cardinality-estimation.log



