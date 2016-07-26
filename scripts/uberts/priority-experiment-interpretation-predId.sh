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
AC=`grep -m 1 agendaComparator $LOG | key-values agendaComparator`
if [[ "$AP" == "" ]]; then AP="noPriority"; fi
if [[ "$AC" == "" ]]; then AP="noComparator"; fi

PERF=`mktemp`
grep 'dev-full.*tp.*predicate2' $LOG | key-values "F(predicate2)" "P(predicate2)" "R(predicate2)" | awk '{print $0, NR}' | sort -rn >$PERF
N=`cat $PERF | wc -l`
N_OPT=`head -n 1 $PERF | awk '{print $4}'`
F=`head -n 1 $PERF | awk '{print $1}'`
P=`head -n 1 $PERF | awk '{print $2}'`
R=`head -n 1 $PERF | awk '{print $3}'`

echo -e "$F\t$P\t$R\t$N_OPT\t$N\t$T\t$GF\t$FS\t$AP\t$AC"

rm $PERF

