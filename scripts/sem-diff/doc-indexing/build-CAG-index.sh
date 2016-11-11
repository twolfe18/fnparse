#!/bin/bash

# This script specifies some CAG input, but in general this is how the
# ikbp.tac.IndexCommunications pipeline should work.

set -eu

OUTPUT_DIR=$1
COMM_GLOB=$2    # e.g. "$CAG/**/nyt_eng_2007*.tar.gz"
JAR=$3

# TODO Consider doing this on NFS
#TEMP_DIR=/state/partition1
TEMP_DIR=$OUTPUT_DIR/tempdir
mkdir -p $TEMP_DIR

CAG=/export/common/data/processed/concrete/concretely-annotated/gigaword/stanford

SCRIPTS=/home/hltcoe/twolfe/fnparse-build/fnparse/scripts

JAR_STABLE="$OUTPUT_DIR/fnparse.jar"
echo "copying jar to safe place:"
echo "    $JAR"
echo "==> $JAR_STABLE"
cp "$JAR" "$JAR_STABLE"

    #communicationArchives "$CAG/**/nyt_eng_2007*.tar.gz" \
#echo "counting strings... `date`"
#mkdir -p "$OUTPUT_DIR/raw"
#java -ea -Xmx32G -cp $JAR \
#  edu.jhu.hlt.ikbp.tac.IndexCommunications \
#    command tokenObs \
#    communicationArchives "$CAG/**/*.tar.gz" \
#    outputTokenObs "$OUTPUT_DIR/tokenObs.jser.gz" \
#    outputTokenObsLower "$OUTPUT_DIR/tokenObs.lower.jser.gz"
echo "copying ngram models into place... `date`"
cp /home/hltcoe/twolfe/character-sequence-counts/pruned/charCounts.lower-false.reverse-false.minCount3.jser.gz "$OUTPUT_DIR/tokenObs.jser.gz"
cp /home/hltcoe/twolfe/character-sequence-counts/pruned/charCounts.lower-true.reverse-false.minCount3.jser.gz "$OUTPUT_DIR/tokenObs.lower.jser.gz"

echo "extracting raw data... `date`"
mkdir -p "$OUTPUT_DIR/raw"
java -ea -Xmx40G -cp $JAR_STABLE \
  edu.jhu.hlt.ikbp.tac.IndexCommunications \
    command extractBasicData \
    communicationArchives "$COMM_GLOB" \
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
  | sort -n -k 1,2 -T "$TEMP_DIR" \
  | $SCRIPTS/sem-diff/doc-indexing/convert-ner-feature-file-format.py \
    "$OUTPUT_DIR/ner_feats"

echo "compacting term-hash mapping... `date`"
zcat "$OUTPUT_DIR/raw/termHash.approx.txt.gz" \
  | sort -n -u -T "$TEMP_DIR" \
  | gzip -c >"$OUTPUT_DIR/raw/termHash.sortu.txt.gz"

echo "extracting situations... `date`"
java -ea -cp $JAR_STABLE \
  edu.jhu.hlt.ikbp.tac.IndexCommunications \
    command indexSituations \
    communicationArchives "$COMM_GLOB" \
    outputDirectory "$OUTPUT_DIR/sit_feats"

echo "building deprel index... `date`"
$SCRIPTS/sem-diff/doc-indexing/prune-deprel-index.sh \
  $OUTPUT_DIR/sit_feats/deprels.txt.gz \
  $OUTPUT_DIR/sit_feats/index_deprel/deprels.txt \
  $OUTPUT_DIR/sit_feats/index_deprel/hashedArgs.txt.gz \
  $OUTPUT_DIR/sit_feats/index_deprel \
  $JAR_STABLE

echo "build unified Tokenization UUID => Communication UUID"
echo "across [entity, deprel, situation] values... `date`"
T2C=$OUTPUT_DIR/tokUuid2commUuid.txt
echo "writing to $T2C"
touch $T2C
zcat $OUTPUT_DIR/raw/emTokCommUuidId.txt.gz | awk '{printf("%s\t%s\n", $2, $3)}' | uniq >>$T2C
cat $OUTPUT_DIR/sit_feats/index_deprel/deprels.txt | awk '{printf("%s\t%s\n", $8, $9)}' | uniq >>$T2C

echo "done. `date`"

