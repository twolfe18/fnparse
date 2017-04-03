#!/bin/bash

set -eu

OUT=feature-selection
mkdir -p $OUT

SHARDS=16

MEM_FREE="14G"
XMX="12G"

for S in `seq $SHARDS`; do
SS=`echo "$S-1" | bc`
SS="$SS/$SHARDS"

qsub -cwd -b y -j y -o $OUT -N mifsA -l "mem_free=$MEM_FREE,num_proc=1,h_rt=48:00:00" \
	java -Xmx$XMX -ea -server -cp fnparse.jar edu.jhu.hlt.entsum.PmiFeatureSelection \
		shard $SS \
		entityDirParent tokenized-sentences/train \
		extractionsAsInstances false \
		addNeg true \
		output $OUT/mi-withNeg-entityInst-shard${S}-of${SHARDS}.txt

qsub -cwd -b y -j y -o $OUT -N mifsB -l "mem_free=$MEM_FREE,num_proc=1,h_rt=48:00:00" \
	java -Xmx$XMX -ea -server -cp fnparse.jar edu.jhu.hlt.entsum.PmiFeatureSelection \
		shard $SS \
		entityDirParent tokenized-sentences/train \
		extractionsAsInstances false \
		addNeg false \
		output $OUT/mi-withoutNeg-entityInst-shard${S}-of${SHARDS}.txt

qsub -cwd -b y -j y -o $OUT -N mifsC -l "mem_free=$MEM_FREE,num_proc=1,h_rt=48:00:00" \
	java -Xmx$XMX -ea -server -cp fnparse.jar edu.jhu.hlt.entsum.PmiFeatureSelection \
		shard $SS \
		entityDirParent tokenized-sentences/train \
		extractionsAsInstances true \
		addNeg true \
		output $OUT/mi-withNeg-extractInst-shard${S}-of${SHARDS}.txt

qsub -cwd -b y -j y -o $OUT -N mifsD -l "mem_free=$MEM_FREE,num_proc=1,h_rt=48:00:00" \
	java -Xmx$XMX -ea -server -cp fnparse.jar edu.jhu.hlt.entsum.PmiFeatureSelection \
		shard $SS \
		entityDirParent tokenized-sentences/train \
		extractionsAsInstances true \
		addNeg false \
		output $OUT/mi-withoutNeg-extractInst-shard${S}-of${SHARDS}.txt
done

