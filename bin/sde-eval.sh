#!/bin/bash

PATH_CMD=$(dirname $0) ;

source ${PATH_CMD}/../conf/sparqlgx.conf

clean=0
saveFile=""
noOptim=""
while true; do
    case "$1" in
	--clean )
	    clean=1
	    shift
	    ;;
	--no-optim )
	    noOptim="--no-optim"
	    shift
	    ;;
	-o )
	    saveFile=$2
	    shift 2
	    ;;
	*)
	    break
	    ;;
    esac
done


if [[ $# != 2 ]];
then
    echo "Usage: $0 [-o responseFile_HDFSPath] [--no-optim] [--clean] queryFile_LocalPath tripleFile_HDFSPath"
    exit 1
fi
queryFile=$1
tripleFile=$2
localpath=$(sed "s|~|$HOME|g" <<< "$SPARQLGX_LOCAL/sde")

########################################################
# Job is done in three main steps:
#  1. The local SPARQL query is translated.
#  2. The translation result is compiled.
#  3. The obtained .jar is executed by spark-submit.
# (4.)Temporary files are removed.
########################################################

# Step 1: Translation.
mkdir -p $localpath/src/main/scala/ ;
bash ${PATH_CMD}/generate-build.sh "SPARQLGX Direct Evaluation" > $localpath/build.sbt
${PATH_CMD}/sparqlgx-translator $queryFile --onefile $noOptim > $localpath/src/main/scala/Query.scala

# Step 2: Compilation.
cd $localpath
rm -f $localpath/target/scala*/sparqlgx-direct-evaluation_*.jar
sbt package
cd - > /dev/null

# Step 3: Execution.
spark-submit --driver-memory $SPARK_DRIVER_MEM \
    --executor-memory $SPARK_EXECUT_MEM \
    --class=Query \
    $localpath/target/scala*/sparqlgx-direct-evaluation_*.jar "$hdfsdbpath" "$saveFile" ;

# Step 4 [optional]: Cleaning.
if [[ $clean == "1" ]]; then rm -rf $localpath ; fi

exit 0
