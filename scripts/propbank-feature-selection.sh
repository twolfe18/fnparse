#$ -cwd
#$ -j y
#$ -V
#$ -l h_rt=72:00:00
#$ -l mem_free=13G
#$ -l num_proc=1
#$ -o logging/propbank/feature-selection
#$ -S /bin/bash

CP=`find target/ -name '*.jar' | tr '\n' ':'`

java -Xmx12G -ea -server -cp ${CP} \
  edu.jhu.hlt.fnparse.rl.rerank.RerankerTrainer "$@"

  

