#!/bin/bash

# See ComputeIdf, uses the c2w table rather than simpleaccumulo tables

JAR=$1

OUTPUT_DIR=/export/projects/twolfe/sit-search/idf

JAR_STABLE=$OUTPUT_DIR/fnparse.jar
cp $JAR $JAR_STABLE

for NS in twolfe-cag1 twolfe-cawiki-en1; do
for NHASH in 8 12; do
for LOGB in 16 20; do
qsub -l "num_proc=1,mem_free=5G,h_rt=48:00:00" -b y -j y -o $OUTPUT_DIR -N "df-$NHASH-$LOGB-$NS" \
  java -ea -cp $JAR_STABLE -Xmx4G \
    edu.jhu.hlt.ikbp.tac.ComputeIdf \
      namespace $NS \
      nhash $NHASH \
      logb $LOGB \
      output $OUTPUT_DIR/df-cms-simpleaccumulo-${NS}-nhash${NHASH}-logb${LOGB}.jser
done
done
done

