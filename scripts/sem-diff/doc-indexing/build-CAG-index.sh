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

#CAG=/export/common/data/processed/concrete/concretely-annotated/gigaword/stanford

#SCRIPTS=/home/hltcoe/twolfe/fnparse-build/fnparse/scripts
SCRIPTS=./scripts

JAR_STABLE="$OUTPUT_DIR/fnparse.jar"
echo "copying jar to safe place:"
echo "    $JAR"
echo "==> $JAR_STABLE"
cp "$JAR" "$JAR_STABLE"

# Building char-ngram-tries is now done offline/AOT
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
#cp /home/hltcoe/twolfe/character-sequence-counts/pruned/charCounts.lower-false.reverse-false.minCount3.jser.gz "$OUTPUT_DIR/tokenObs.jser.gz"
#cp /home/hltcoe/twolfe/character-sequence-counts/pruned/charCounts.lower-true.reverse-false.minCount3.jser.gz "$OUTPUT_DIR/tokenObs.lower.jser.gz"
cp ~/code/fnparse/data/character-sequence-counts/charCounts.apw_eng_2000on.lower-false.reverse-false.minCount3.jser.gz "$OUTPUT_DIR/tokenObs.jser.gz"
cp ~/code/fnparse/data/character-sequence-counts/charCounts.apw_eng_2000on.lower-true.reverse-false.minCount3.jser.gz "$OUTPUT_DIR/tokenObs.lower.jser.gz"


### NER/ENTITY and DOCUMENT FEATURES
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
  | LC_COLLATE=C sort -n -t '	' -k 1,2 -T "$TEMP_DIR" \
  | $SCRIPTS/sem-diff/doc-indexing/convert-ner-feature-file-format.py \
    "$OUTPUT_DIR/ner_feats"

echo "compacting term-hash mapping... `date`"
zcat "$OUTPUT_DIR/raw/termHash.approx.txt.gz" \
  | LC_COLLATE=C sort -n -u -T "$TEMP_DIR" \
  | gzip -c >"$OUTPUT_DIR/raw/termHash.sortu.txt.gz"


### DEPRELS
echo "extracting situations... `date`"
java -ea -cp $JAR_STABLE \
  edu.jhu.hlt.ikbp.tac.IndexCommunications \
    command indexDeprels \
    communicationArchives "$COMM_GLOB" \
    outputDirectory "$OUTPUT_DIR/deprel"

echo "building deprel index... `date`"
#$SCRIPTS/sem-diff/doc-indexing/prune-deprel-index.sh \
#  $OUTPUT_DIR/deprel/deprels.txt.gz \
#  $OUTPUT_DIR/deprel/pruned_indexed/deprels.txt \
#  $OUTPUT_DIR/deprel/pruned_indexed/hashedArgs.txt.gz \
#  $OUTPUT_DIR/deprel/pruned_indexed \
#  $JAR_STABLE
zcat $OUTPUT_DIR/deprel/deprels.txt.gz \
  | awk -F"\t" '{printf("%s\t%s/%s_%s/%s\n", $1, $3, $4, $6, $7)}' \
  | LC_COLLATE=C sort | uniq -c | awk '{printf("%s\t%s\t%d\n", $2, $3, $1)}' \
  | $SCRIPTS/sem-diff/doc-indexing/prune-matrix-factorization.sh \
    8 8 3 3 $TEMP_DIR/deprel \
    >$OUTPUT_DIR/deprel/deprels.pruned.txt


### FRAMES
echo "extracting frames `date`"
java -ea -cp $JAR_STABLE \
  edu.jhu.hlt.ikbp.tac.IndexCommunications \
    command indexFrames \
    communicationArchives "$COMM_GLOB" \
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

