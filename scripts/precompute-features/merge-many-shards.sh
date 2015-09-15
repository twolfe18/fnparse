#!/bin/bash

# Given a file/list of working directories created by FeaturePrecomputation,
# run a single merge-two-shards.sh job on all pairs, passing along the remainder.

set -e

if [[ $# != 4 ]]; then
  echo "please provide:"
  echo "1) a file of (hint: you can use <(find foo -type d -iname 'bar*'))"
  echo "2) an output directory"
  echo "3) a unique job name"
  echo "4) a jar file"
  exit 1
fi

INPUT_JOB_DIRS=$1     # use bash trick: <(find foo -type d)
OUTPUT_DIR=$2
NAME=$3
JAR=$4

mkdir -p $OUTPUT_DIR/sge-logs

echo "copyinig jar to a safe place..."
JAR_STABLE=$OUTPUT_DIR/fnparse.jar
echo "    $JAR"
echo "==> $JAR_STABLE"
cp $JAR $JAR_STABLE

PREV=""
OUT_IDX=0
for CUR in `cat $INPUT_JOB_DIRS`; do
  #echo $CUR
  if [[ "$PREV" ]]; then
    OUTPUT=$OUTPUT_DIR/merged-$OUT_IDX
    mkdir -p $OUTPUT
    qsub -N $NAME-$OUT_IDX -o $OUTPUT_DIR/sge-logs \
      ./scripts/precompute-features/merge-two-shards.sh \
        $PREV \
        $CUR \
        $OUTPUT \
        $JAR_STABLE
    PREV=""
    OUT_IDX=`echo $OUT_IDX | awk '{print $1 + 1}'`
  else
    PREV=$CUR
  fi
done

# Copy the odd/last item
if [[ "$PREV" ]]; then
  OUTPUT=$OUTPUT_DIR/merged-$OUT_IDX
  ln -s $PREV $OUTPUT
fi





