package akka.dispatch

import java.util

import akka.actor.ActorCell
import mesosphere.marathon.core.async.{ Context, ContextPropagatingExecutionContext, propagateContext }
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.{ Around, Aspect }
import org.slf4j.MDC

import scala.concurrent.duration.{ Duration, FiniteDuration }

class ContextWrapper(val invocation: Envelope, val context: Map[Context.ContextName[_], Any], val mdc: Option[util.Map[String, String]])

object ContextWrapper {
  def unapply(cw: ContextWrapper): Option[(Envelope, Map[Context.ContextName[_], Any], Option[util.Map[String, String]])] =
    Some((cw.invocation, cw.context, cw.mdc))
}

@Aspect
private class WeaveActorReceive {
  @Around("execution(* akka.actor..ActorCell.invoke(..)) && args(envelope)")
  def contextInvoke(pjp: ProceedingJoinPoint, envelope: Envelope): Any = {
    envelope match {
      case Envelope(ContextWrapper(originalEnvelope, context, mdc), _) =>
        propagateContext(context, mdc)(pjp.proceed(Array(originalEnvelope)))
      case _ =>
        pjp.proceed(Array(envelope))
    }
  }
}

class ContextPropagatingDispatcher(
  configurator: MessageDispatcherConfigurator,
  id: String,
  throughput: Int,
  throughputDeadlineTime: Duration,
  executorServiceFactoryProvider: ExecutorServiceFactoryProvider,
  shutdownDeadlineTime: FiniteDuration)
    extends Dispatcher(configurator, id, throughput, throughputDeadlineTime, executorServiceFactoryProvider, shutdownDeadlineTime)
    with ContextPropagatingExecutionContext {

  override protected[akka] def dispatch(receiver: ActorCell, invocation: Envelope): Unit = {
    super.dispatch(receiver, Envelope(new ContextWrapper(invocation, Context.copy, Option(MDC.getCopyOfContextMap)), invocation.sender))
  }
}