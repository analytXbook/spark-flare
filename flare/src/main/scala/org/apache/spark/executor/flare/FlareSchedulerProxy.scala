package org.apache.spark.executor.flare

import org.apache.spark.internal.Logging
import org.apache.spark.flare.{DriverData, FlareCluster}
import org.apache.spark.rpc.{RpcCallContext, RpcEndpointRef, RpcEnv}
import org.apache.spark.scheduler.flare.FlareMessages._
import org.apache.spark.scheduler.flare.FlareSchedulerBackend

import scala.util.{Failure, Success}

private[spark] class FlareSchedulerProxy(
    cluster: FlareCluster,
    idBackend: FlareIdBackend,
    override val rpcEnv: RpcEnv)
  extends FlareDriverProxyEndpoint(FlareSchedulerBackend.ENDPOINT_NAME, cluster, idBackend) with Logging {

  var executorRef: RpcEndpointRef = _
  var registerMsg: RegisterExecutor = _

  override def receive: PartialFunction[Any, Unit] = {
    case _statusUpdate @ StatusUpdate(executorId, taskId, state, data) => {
      val driver = driverId(taskId, "task")
      logDebug(s"Sending status update $taskId TID $state -> driver $driver")
      driverRefs.get(driver).map(_.send(_statusUpdate))
    }
    case reservation: FlareReservation => {
      executorRef.send(reservation)
    }

    case _cancelReservation @ CancelReservation(reservationId) => {
      executorRef.send(_cancelReservation)
    }
  }

  private def registerWithDriver(driverId: Int, driverRef: RpcEndpointRef) = {
    logInfo(s"Registering with driver ${driverId} scheduler")

    driverRef.ask[RegisteredExecutorResponse](registerMsg) onComplete {
      case Success(response) => {
        response match {
          case RegisteredExecutor => logInfo(s"Successfully registered with driver $driverId")
          case RegisterExecutorFailed(msg) => logError(s"Error registering with driver $driverId: $msg")
        }
      }
      case Failure(error) => {
        logError(s"Error registering with driver $driverId: $error")
      }
    }
  }

  override def receiveAndReply(context: RpcCallContext): PartialFunction[Any, Unit] = {
    case _registerExecutor @ RegisterExecutor(executorId, _executorRef, cores, logUrls) => {
      executorRef = _executorRef
      registerMsg = RegisterExecutor(executorId, self, cores, logUrls)
      context.reply(RegisteredExecutor)
      driverRefs.foreach {
        case (driverId, driverRef) => {
          registerWithDriver(driverId, driverRef)
        }
      }
    }
    case allocateIds: AllocateIds => {
      pipe(allocateIds, executorRef, context)
    }
    case _redeemReservation @ RedeemReservation(reservationId, _, _) => {
      pipe(_redeemReservation, driverRefs(reservationId.driverId), context)
    }
  }

  override def onDriverJoined(data: DriverData) = {
    super.onDriverJoined(data)

    registerWithDriver(data.driverId, driverRefs(data.driverId))
  }

}