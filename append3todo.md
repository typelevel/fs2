Following needs to be done before we can merge this new representation into master. 
Please remove from the list once done....

## Process.scala

### Process trait
    
- [ ] Process.gatherMap

### Process object 
 
- [ ] Process.fill
- [x] Process.iterate
- [x] Process.state
- [x] Process.duration
- [x] Process.every

- [x] Process.sleepUntil
- [ ] Process.awakeEvery - fix
- [x] Process.ranges
- [x] Process.supply
- [x] Process.toTask
- [ ] Process.forwardFill


### instances

- [x] MonadPlus[Process]


### ProcessSyntax: 

- [ ] through_y
- [ ] connect
- [ ] feed

### WriterSyntax 
- [ ] connectW
- [ ] drainW
- [ ] connectO
- [ ] drainO

### SourceSyntax
- [ ] connectTimed
- [ ] forwardFill
- [ ] toTask

### Process0Syntax
- [ ] improve // check toIndexedSeq

### Process1Syntax
- [X] *  

### TeeSyntax
- [X] *

### WyeSyntax
- [ ] detachL -> detach1L
- [ ] detachR -> detach1R

### ChannelSyntax
- [X] *

### SinkTaskSyntax
- [X] *

### EvalProcess
- [ ] *



## nio/*.scala
- [ ] * 

## compress.scala
- [ ] *

## Exchange.scala
- [X] mapW
- [X] pipeW
- [X] wye
- [X] readThrough
- [X] Exchange.loopBack

 
## hash.scala
- [ ] *

## io.scala
- [ ] *

## package.scala - Semigroup instance
- [x] *
  
## process1.scala 
- [x] unchunk 
- [ ] init
- [ ] liftY
- [ ] record
- [x] prefixSums
- [ ] suspend1 - not sure if fits in new repre.
- [x] zipWith*

## text.scala
- [ ] *
 
## wye.scala 
- [ ] current implementation of detachL/R => detach1L/1R

-----

# Specifications

## CompressSpec.scala
- [ ] *

## ExchangeSpec.scala
- [X] *

## HashSpec.scala
- [ ] *

## MergeNSpec.scala
- [ ] fix mergeN drain

## NioSpec.scala
- [ ] *
 
## ProcessSpec.scala
- [ ] fill
- [ ] forwardFill
- [ ] enqueue
- [ ] state  - fix
- [ ] pipeIn
- [ ] pipeIn reuse 
 
 
## Process1Spec.scala
- [x] unchunk 
- [ ] init
- [ ] liftY
- [ ] record
- [x] prefixSums
- [ ] suspend1 - not sure if fits in new repre.
- [x] zipWith* 
 
## UnsafeChunkRSpec
- [ ] *
 
## Utf8DecodeSpec 
- [ ] *
    
