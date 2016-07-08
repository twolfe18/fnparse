#!/bin/bash

set -eu
set -o pipefail

LOG=$1

T=`echo $LOG | awk -F"." '{print $NF}'`

# feature set, global feature mode, agenda priority, full performance

#FS=`grep getFeatureSetString $LOG | awk '{print $NF}'`
FS="ukn"

GF=`grep -m 1 globalFeatMode $LOG | key-values globalFeatMode`

AP=`grep -m 1 -oP '(?<=parsing agenda priority ).*' $LOG`

PERF=`mktemp`
grep 'dev-full.*argument4' $LOG >$PERF
N=`cat $PERF | wc -l`
PL=`cat $PERF | tail -n 1`
F=`echo $PL | key-values "F(argument4)"`
P=`echo $PL | key-values "P(argument4)"`
R=`echo $PL | key-values "R(argument4)"`

echo -e "$F\t$P\t$R\t$N\t$T\t$GF\t$FS\t$AP"

rm $PERF

