#!/bin/bash

# This script specifies some CAG input, but in general this is how the
# ikbp.tac.IndexCommunications pipeline should work.

set -eu

OUTPUT_DIR=$1
DATA_PROFILE=$2   # e.g. "CAG_SMALL"
DATA_PROVIDER=$3  # either "scion" or "disk:/path/to/CAG/root"
TOK_OBS=$4      # see tutils.TokenObservationCounts, data e.g. /home/hltcoe/twolfe/character-sequence-counts/pruned/charCounts.lower-false.reverse-false.minCount3.jser.gz
TOK_OBS_LC=$5   # see tutils.TokenObservationCounts, data e.g. /home/hltcoe/twolfe/character-sequence-counts/pruned/charCounts.lower-true.reverse-false.minCount3.jser.gz
SCRIPTS=$6      # e.g. ~/fnparse-build/fnparse/scripts
JAR=$7

ACCUMULO_USER="reader"
ACCUMULO_PASSWORD="an accumulo reader"

echo "args: $@"

if [[ ! -f $JAR ]]; then
  echo "couldn't find jar: $JAR"
  exit 1
fi

mkdir -p "$OUTPUT_DIR"

# TODO Consider doing this on NFS
#TEMP_DIR=/state/partition1
TEMP_DIR=$OUTPUT_DIR/tempdir
mkdir -p $TEMP_DIR

#SCRIPTS=/home/hltcoe/twolfe/fnparse-build/fnparse/scripts
#SCRIPTS=./scripts
#THIS_SCRIPT_DIR=`dirname $(readlink -f $0)`
#SCRIPTS=$THIS_SCRIPT_DIR/../..
echo "SCRIPTS=$SCRIPTS"

if [[ ! -f $TOK_OBS ]]; then
  echo "TOK_OBS=$TOK_OBS is not a file"
  exit 1
fi
if [[ ! -f $TOK_OBS_LC ]]; then
  echo "TOK_OBS_LC=$TOK_OBS_LC is not a file"
  exit 2
fi
if [[ ! -f $JAR ]]; then
  echo "JAR=$JAR is not a file"
  exit 3
fi

JAR_STABLE="$OUTPUT_DIR/fnparse.jar"
echo "copying jar to safe place:"
echo "    $JAR"
echo "==> $JAR_STABLE"
cp "$JAR" "$JAR_STABLE"

echo "copying ngram models into place... `date`"
cp $TOK_OBS "$OUTPUT_DIR/tokenObs.jser.gz"
cp $TOK_OBS_LC "$OUTPUT_DIR/tokenObs.lower.jser.gz"


### NER/ENTITY and DOCUMENT FEATURES
echo "extracting raw data... `date`"
mkdir -p "$OUTPUT_DIR/raw"
java -ea -Xmx40G -cp $JAR_STABLE \
  -Dscion.accumulo.user="$ACCUMULO_USER" -Dscion.accumulo.password="$ACCUMULO_PASSWORD" \
  edu.jhu.hlt.ikbp.tac.IndexCommunications \
    command extractBasicData \
    dataProfile "$DATA_PROFILE" \
    dataProvider "$DATA_PROVIDER" \
    outputDirectory "$OUTPUT_DIR/raw" \
    tokenObs "$OUTPUT_DIR/tokenObs.jser.gz" \
    tokenObsLower "$OUTPUT_DIR/tokenObs.lower.jser.gz"

echo "building document vectors... `date`"
mkdir -p "$OUTPUT_DIR/doc"
java -ea -cp $JAR_STABLE \
  edu.jhu.hlt.ikbp.tac.IndexCommunications \
    command buildDocVecs \
    numTerms 128 \
    input "$OUTPUT_DIR/raw/termDoc.txt.gz" \
    outputDocVecs "$OUTPUT_DIR/doc/docVecs.128.txt.gz" \
    outputIdf "$OUTPUT_DIR/doc/idf.txt"

echo "building ner feature indices... `date`"
mkdir -p "$OUTPUT_DIR/ner_feats"
time zcat "$OUTPUT_DIR/raw/nerFeatures.txt.gz" \
  | LC_COLLATE=C LC_ALL=C sort -n -t '	' -k 1,2 --buffer-size=2G -T "$TEMP_DIR" \
  | gzip -c >"$OUTPUT_DIR/ner_feats/sorted-all.txt.gz"
zcat "$OUTPUT_DIR/ner_feats/sorted-all.txt.gz" \
  | $SCRIPTS/sem-diff/doc-indexing/convert-ner-feature-file-format.py \
    "$OUTPUT_DIR/ner_feats"

echo "compacting term-hash mapping... `date`"
zcat "$OUTPUT_DIR/raw/termHash.approx.txt.gz" \
  | LC_COLLATE=C LC_ALL=C sort -n -u --buffer-size=2G -T "$TEMP_DIR" \
  | gzip -c >"$OUTPUT_DIR/raw/termHash.sortu.txt.gz"


### DEPRELS
echo "extracting deprels `date`"
java -ea -cp $JAR_STABLE \
  -Dscion.accumulo.user="$ACCUMULO_USER" -Dscion.accumulo.password="$ACCUMULO_PASSWORD" \
  edu.jhu.hlt.ikbp.tac.IndexCommunications \
    command indexDeprels \
    dataProfile "$DATA_PROFILE" \
    dataProvider "$DATA_PROVIDER" \
    outputDirectory "$OUTPUT_DIR/deprel"

#echo "building deprel inverted index... `date`"
#zcat $OUTPUT_DIR/deprel/deprels.txt.gz \
#  | awk -F"\t" '{printf("%s\t%s/%s_%s/%s\n", $1, $3, $4, $6, $7)}' \
#  | gzip -c >"$OUTPUT_DIR/deprel/rel-arg-tok.txt.gz"

echo "building deprel matrix (sorting)... `date`"
zcat $OUTPUT_DIR/deprel/deprels.txt.gz \
  | awk -F"\t" '{printf("%s\t%s/%s_%s/%s\n", $1, $3, $4, $6, $7)}' \
  | LC_COLLATE=C LC_ALL=C sort --buffer-size=2G \
  | uniq -c \
  | awk '{printf("%s\t%s\t%d\n", $2, $3, $1)}' \
  | gzip -c >"$OUTPUT_DIR/deprel/sorted-all.txt.gz"
echo "building deprel matrix (splitting)... `date`"
zcat "$OUTPUT_DIR/deprel/sorted-all.txt.gz" \
  | $SCRIPTS/sem-diff/doc-indexing/prune-matrix-factorization.sh \
    8 8 3 3 $TEMP_DIR/deprel \
    >$OUTPUT_DIR/deprel/deprels.pruned.txt


### FRAMES
echo "extracting frames `date`"
java -ea -cp $JAR_STABLE \
  -Dscion.accumulo.user="$ACCUMULO_USER" -Dscion.accumulo.password="$ACCUMULO_PASSWORD" \
  edu.jhu.hlt.ikbp.tac.IndexCommunications \
    command indexFrames \
    dataProfile "$DATA_PROFILE" \
    dataProvider "$DATA_PROVIDER" \
    outputDirectory "$OUTPUT_DIR/frame"


### MISC
echo "build unified Tokenization UUID => Communication UUID"
echo "across [entity, deprel, frame] values... `date`"
T2C=$OUTPUT_DIR/tokUuid2commUuid.txt
echo "writing to $T2C"
touch $T2C
zcat $OUTPUT_DIR/raw/emTokCommUuidId.txt.gz | awk -F"\t" '{printf("%s\t%s\n", $2, $3)}' | uniq >>$T2C
zcat $OUTPUT_DIR/deprel/deprels.txt.gz | awk -F"\t" '{printf("%s\t%s\n", $8, $9)}' | uniq >>$T2C
zcat $OUTPUT_DIR/frame/tokUuid_commUuid_commId.txt.gz | awk -F"\t" '{printf("%s\t%s\n", $1, $2)}' | uniq >>$T2C


echo "done. `date`"

