Following needs to be done before we can merge this new representation into master. 
Please remove from the list once done....

## Process.scala

### Process trait
    
-[ ] Process.gatherMap

### Process object 
 
-[ ] Process.fill
-[ ] Process.iterate
-[ ] Process.state
-[ ] Process.duration
-[ ] Process.every

-[ ] Process.sleepUntil
-[ ] Process.awakeEvery - fix
-[ ] Process.ranges
-[ ] Process.supply
-[ ] Process.toTask
-[ ] Process.forwardFill


### instances

- [ ] MonadPlus[Process]


### ProcessSyntax: 

-[ ] through_y
-[ ] connect
-[ ] feed

### WriterSyntax 
-[ ] connectW
-[ ] drainW
-[ ] connectO
-[ ] drainO

### SourceSyntax
-[ ] connectTimed
-[ ] forwardFill
-[ ] toTask

### Process0Syntax
-[ ] improve // check toIndexedSeq

### Process1Syntax
-[ ] *  

### TeeSyntax
-[ ] *

### WyeSyntax
-[ ] *

### ChannelSyntax
-[ ] *

### SinkTaskSyntax
-[ ] *

### EvalProcess
-[ ] *



## nio/*.scala
-[ ] * 

## compress.scala
-[ ] *

## Exchange.scala
-[ ] mapW
-[ ] pipeW
-[ ] wye
-[ ] readThrough
-[ ] Exchange.loopBack

 
## hash.scala
-[ ] *

## io.scala
-[ ] *

## package.scala - Semigroup instance
-[ ] *
  
## process1.scala 
-[ ] unchunk 
-[ ] init
-[ ] liftY
-[ ] record
-[ ] prefixSums
-[ ] suspend1 - not sure if fits in new repre.
-[ ] zipWith*

## text.scala
-[ ] *
 
## wye.scala 
-[ ] current implementation of detachL/R => detach1L/1R

-----

# Specifications

## CompressSpec.scala
-[ ] *

## ExchangeSpec.scala
-[ ] *

## HashSpec.scala
-[ ] *

## MergeNSpec.scala
-[ ] fix mergeN drain

## NioSpec.scala
-[ ] *
 
## Process1Spec.scala
-[ ] unchunk 
-[ ] init
-[ ] liftY
-[ ] record
-[ ] prefixSums
-[ ] suspend1 - not sure if fits in new repre.
-[ ] zipWith* 
 
## UnsafeChunkRSpec
-[ ] *
 
## Utf8DecodeSpec 
-[ ] *
    