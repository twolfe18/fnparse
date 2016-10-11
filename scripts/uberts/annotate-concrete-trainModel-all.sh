
make jar
JAR=target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar

FNPARSE_DATA=/export/projects/twolfe/fnparse-data

MODEL_OUT=/export/projects/twolfe/fnparse-models/concretely-annotated-gigaword/2016-10-11
mkdir -p $MODEL_OUT


qsub -N "cag-fn-fnparse" -l "mem_free=6G,num_proc=1" \
  ./scripts/uberts/annoate-concrete-trainModel-fn.sh $MODEL_OUT/fn $FNPARSE_DATA $JAR

qsub -N "cag-pb-fnparse" -l "mem_free=6G,num_proc=1" \
  ./scripts/uberts/annoate-concrete-trainModel-pb.sh $MODEL_OUT/pb $FNPARSE_DATA $JAR

