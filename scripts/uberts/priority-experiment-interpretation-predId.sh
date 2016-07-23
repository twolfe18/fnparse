#!/bin/bash

#set -eu
#set -o pipefail

LOG=$1

T=`echo $LOG | awk -F"." '{print $NF}'`

# feature set, l2r, frames, agenda priority, full performance

#FS=`grep getFeatureSetString $LOG | awk '{print $NF}'`
FS="ukn"

#GF=`grep -m 1 globalFeatMode $LOG | key-values globalFeatMode`
#GF=`grep -m 1 -oP 'allowableGlobals=\S*' $LOG`
GF=`grep 'GlobalParams::<init>' $LOG | awk '{print $NF}' | tr '\n' '_' | sed 's/_$//'`
if [[ "$GF" == "" ]]; then
GF="none";
fi

AP=`grep -m 1 -oP '(?<=parsing agenda priority ).*' $LOG`

PERF=`mktemp`
grep 'dev-full.*tp.*predicate2' $LOG >$PERF
N=`cat $PERF | wc -l`

# TODO Take the max instead of the last?
PL=`key-values 'F(predicate2)' 'P(predicate2)' 'R(predicate2)' <$PERF | awk 'BEGIN{p=0;r=0;f=0} $1>f {f=$1; p=$2; r=$3} END {print f, p, r}'`
F=`echo $PL | cut -d' ' -f1`
P=`echo $PL | cut -d' ' -f2`
R=`echo $PL | cut -d' ' -f3`
#PL=`cat $PERF | tail -n 1`
#F=`echo $PL | key-values "F(predicate2)"`
#P=`echo $PL | key-values "P(predicate2)"`
#R=`echo $PL | key-values "R(predicate2)"`


echo -e "$F\t$P\t$R\t$N\t$T\t$GF\t$FS\t$AP"

rm $PERF

