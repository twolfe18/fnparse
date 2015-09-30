
set -eu

if [[ $# != 2 ]]; then
  echo "please provide:"
  echo "1) a working directory"
  echo "2) propbank, i.e. either \"true\" or \"false\""
  exit 1
fi

# e.g. /export/projects/twolfe/fnparse-output/experiments/precompute-features/propbank/sep14b/final-results
WD=$1
PROPBANK=$2

mkdir -p $WD/sge-logs
#make jar
JAR=target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar
JAR_STABLE=$WD/fnparse.jar
echo "copying jar to a safe place:"
echo "    $JAR"
echo "==> $JAR_STABLE"
cp $JAR $JAR_STABLE


if [[ $PROPBANK == "true" ]]; then
  MEM_OFFSET=20
  DD=/export/projects/twolfe/fnparse-output/experiments/precompute-features/propbank/sep14b
else if [[ $PROPBANK == "false" ]]; then
  MEM_OFFSET=10
  DD=/export/projects/twolfe/fnparse-output/experiments/precompute-features/framenet/sep29a
else
  echo "must provide a boolean for propbank: $PROPBANK"
  exit 2
fi


for DIM in 10 20 40 80 160 320 640; do
  MEM=`echo "0.11 * $DIM + $MEM_OFFSET" | bc | perl -pe 's/(\d+)\.\d+/\1/'`
  MEM_SGE=`echo "$MEM + 2" | bc`
  for MODE in FULL LOCAL ARG-LOCATION NUM-ARGS ROLE-COOC; do
    FF=scripts/having-a-laugh/propbank-${DIM}.fs
    WDJ=$WD/wd-${MODE}-${DIM}
    mkdir -p $WDJ
    qsub \
      -l "mem_free=${MEM_SGE}G" \
      -N "fr-${MODE}-${DIM}" \
      -o $WD/sge-logs \
      scripts/precompute-features/train.sh \
        $DD \
        $PROPBANK \
        $FF \
        $MODE \
        $WDJ \
        $JAR_STABLE \
        "${MEM}"
  done
done

