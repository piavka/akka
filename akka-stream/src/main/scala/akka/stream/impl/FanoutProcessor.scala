package akka.stream.impl

import akka.actor.{ Actor, ActorRef }
import akka.stream.MaterializerSettings
import org.reactivestreams.Subscriber

/**
 * INTERNAL API
 */
private[akka] abstract class FanoutOutputs(val maxBufferSize: Int, val initialBufferSize: Int, self: ActorRef, val pump: Pump)
  extends DefaultOutputTransferStates
  with SubscriberManagement[Any] {

  override type S = ActorSubscriptionWithCursor[_ >: Any]
  override def createSubscription(subscriber: Subscriber[_ >: Any]): S =
    new ActorSubscriptionWithCursor(self, subscriber)

  protected var exposedPublisher: ActorPublisher[Any] = _

  private var downstreamBufferSpace: Long = 0L
  private var downstreamCompleted = false
  override def demandAvailable = downstreamBufferSpace > 0
  override def demandCount: Long = downstreamBufferSpace

  override val subreceive = new SubReceive(waitingExposedPublisher)

  def enqueueOutputElement(elem: Any): Unit = {
    downstreamBufferSpace -= 1
    pushToDownstream(elem)
  }

  def complete(): Unit =
    if (!downstreamCompleted) {
      downstreamCompleted = true
      completeDownstream()
    }

  def cancel(e: Throwable): Unit = {
    if (!downstreamCompleted) {
      downstreamCompleted = true
      abortDownstream(e)
    }
    if (exposedPublisher ne null) exposedPublisher.shutdown(Some(e))
  }

  def isClosed: Boolean = downstreamCompleted

  def afterShutdown(): Unit

  override protected def requestFromUpstream(elements: Long): Unit = downstreamBufferSpace += elements

  private def subscribePending(): Unit =
    exposedPublisher.takePendingSubscribers() foreach registerSubscriber

  override protected def shutdown(completed: Boolean): Unit = {
    if (exposedPublisher ne null) {
      if (completed) exposedPublisher.shutdown(None)
      else exposedPublisher.shutdown(Some(new IllegalStateException("Cannot subscribe to shutdown publisher")))
    }
    afterShutdown()
  }

  override protected def cancelUpstream(): Unit = {
    downstreamCompleted = true
  }

  protected def waitingExposedPublisher: Actor.Receive = {
    case ExposedPublisher(publisher) ⇒
      exposedPublisher = publisher
      subreceive.become(downstreamRunning)
    case other ⇒
      throw new IllegalStateException(s"The first message must be ExposedPublisher but was [$other]")
  }

  protected def downstreamRunning: Actor.Receive = {
    case SubscribePending ⇒
      subscribePending()
    case RequestMore(subscription, elements) ⇒
      // FIXME can we avoid this cast?
      moreRequested(subscription.asInstanceOf[ActorSubscriptionWithCursor[Any]], elements)
      pump.pump()
    case Cancel(subscription) ⇒
      // FIXME can we avoid this cast?
      unregisterSubscription(subscription.asInstanceOf[ActorSubscriptionWithCursor[Any]])
      pump.pump()
  }

}

/**
 * INTERNAL API
 */
private[akka] class FanoutProcessorImpl(
  _settings: MaterializerSettings,
  initialFanoutBufferSize: Int,
  maximumFanoutBufferSize: Int) extends ActorProcessorImpl(_settings) {

  override val primaryOutputs: FanoutOutputs =
    new FanoutOutputs(maximumFanoutBufferSize, initialFanoutBufferSize, self, this) {
      override def afterShutdown(): Unit = afterFlush()
    }

  val running: TransferPhase = TransferPhase(primaryInputs.NeedsInput && primaryOutputs.NeedsDemand) { () ⇒
    primaryOutputs.enqueueOutputElement(primaryInputs.dequeueInputElement())
  }

  override def fail(e: Throwable): Unit = {
    // FIXME: escalate to supervisor
    log.debug("fail {} due to: {}", self, e.getMessage)
    primaryInputs.cancel()
    primaryOutputs.cancel(e)
    // Stopping will happen after flush
  }

  override def pumpFinished(): Unit = {
    primaryInputs.cancel()
    primaryOutputs.complete()
  }

  def afterFlush(): Unit = context.stop(self)

  nextPhase(running)
}
