package scalaz

package object stream {

  type Process0[+O] = Process.Process0[O]
  type Process1[-I,+O] = Process.Process1[I,O]
  type Tee[-I,-I2,+O] = Process.Tee[I,I2,O] 
  type Wye[-I,-I2,+O] = Process.Wye[I,I2,O] 
  type Sink[+F[_],-O] = Process.Sink[F,O]
  type Channel[+F[_],-I,O] = Process.Channel[F,I,O]

  type Writer[+F[_],+W,+A] = Process.Writer[F, W, A]
  type Process1W[+W,-I,+O] = Process.Process1W[W,I,O]
  type TeeW[+W,-I,-I2,+O] = Process.TeeW[W,I,I2,O]
  type WyeW[+W,-I,-I2,+O] = Process.WyeW[W,I,I2,O]
}
