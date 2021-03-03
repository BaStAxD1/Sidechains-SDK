package com.horizen.websocket.server

import java.util

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import javax.websocket.{OnClose, OnError, OnMessage, OnOpen, SendHandler, SendResult, Session}
import javax.websocket.server.ServerEndpoint
import scorex.util.ScorexLogging

abstract class RequestType(val code:Int)
case object GET_SINGLE_BLOCK_REQUEST_TYPE extends RequestType(0)
case object GET_NEW_BLOCK_HASHES_REQUEST_TYPE extends RequestType(2)
case object GET_MEMPOOL_TXS extends RequestType(4)
case object GET_RAW_MEMPOOL extends RequestType(5)

abstract class MsgType(val code:Int)
case object RESPONSE_MESSAGE extends MsgType(2)
case object EVENT_MESSAGE extends MsgType(0)
case object ERROR_MESSAGE extends MsgType(3)

@ServerEndpoint("/")
class WebSocketServerEndpoint(){
  private val mapper = new ObjectMapper().registerModule(DefaultScalaModule)
  val sidechainNodeChannel: SidechainNodeChannelImpl = new SidechainNodeChannelImpl()

  @OnOpen
  def  onOpen(session: Session): Unit = {
    synchronized{
      WebSocketServerEndpoint.addSession(session);
    }
  }

  @OnClose
  def onClose(session: Session): Unit = {
    synchronized{
      WebSocketServerEndpoint.removeSession(session);
    }
  }

  @OnError
  def onError(session: Session, t:Throwable): Unit = {
    System.out.println("Error on session "+session.getId+": "+t.toString)
    synchronized{
      WebSocketServerEndpoint.removeSession(session);
    }
  }

  @OnMessage
  def onMessageReceived(session: Session, message: String): Unit = {
    try {
      val json = mapper.readTree(message)
      if (json.has("msgType")) {
        json.get("msgType").asInt() match {
          case 1 => // Event
            processRequest(json, session)
          case 2 => // Error
            processError(json, session)
          case msgType =>
            processError(json, session)
            System.out.println("Unknown message received with type = " + msgType)
        }
      } else {
        processError(json, session)
        System.out.println("Unknown message received!")
      }
    } catch {
      case ex: Throwable =>
        processError( mapper.createObjectNode(), session)
        System.out.println("On receive message processing exception occurred = " + ex.getMessage)
    }
  }

  def processRequest(json: JsonNode,session: Session): Unit = {
    val requestPayload = json.get("requestPayload")
    val requestId  = json.get("requestId").asInt()
    val requestType = json.get("requestType").asInt()

    requestType match {
      case GET_SINGLE_BLOCK_REQUEST_TYPE.code => // Get single block
        if (requestPayload.has("hash")) {
          val hash = requestPayload.get("hash").asText()
          val responsePayload = sidechainNodeChannel.getBlockByHash(hash).get
          responsePayload.size match {
            case 0 =>
              WebSocketServerEndpoint.sendError(requestId, requestType, 5, "Invalid parameter", session)
            case _ =>
              WebSocketServerEndpoint.sendMessage(RESPONSE_MESSAGE.code, requestId, requestType, responsePayload, session)
          }
        }
        else if (requestPayload.has("height")) {
          val height = requestPayload.get("height").asInt()
          val responsePayload = sidechainNodeChannel.getBlockByHeight(height).get
          responsePayload.size match {
            case 0 =>
              WebSocketServerEndpoint.sendError(requestId, requestType, 5, "Invalid parameter", session)
            case _ =>
              WebSocketServerEndpoint.sendMessage(RESPONSE_MESSAGE.code, requestId, requestType, responsePayload, session)
          }

        }
        else processError(json, session)

      case GET_NEW_BLOCK_HASHES_REQUEST_TYPE.code => // Get new block hashes
        val afterHash = requestPayload.get("locatorHashes").elements()

        var hashes: Seq[String] = Seq()
        while ( {
          afterHash.hasNext
        }) {
          hashes = hashes :+ afterHash.next().asText()
        }

        val limit = requestPayload.get("limit").asInt()
        if (limit > 50)
          WebSocketServerEndpoint.sendError(requestId,requestType,4,"Invalid limit size! Max limit is 50", session)
        else {
          val responsePayload = sidechainNodeChannel.getNewBlockHashes(hashes, limit).get
          responsePayload match {
            case null =>
              WebSocketServerEndpoint.sendError(requestId, requestType, 4, "Couldn't find new block hashes", session)
            case _ =>
              WebSocketServerEndpoint.sendMessage(RESPONSE_MESSAGE.code, requestId, requestType, responsePayload, session)
          }
        }

      case GET_MEMPOOL_TXS.code => // Get mempool txes
        val txids = requestPayload.get("hash").elements()

        var hashes: Seq[String] = Seq()
        while ( {
          txids.hasNext
        }) {
          hashes = hashes :+ txids.next().asText()
        }
        if (hashes.size > 10) {
          WebSocketServerEndpoint.sendError(requestId, requestType, 4,  "Exceed max number of transactions (10)!", session)
        }
        else {
          val responsePayload = sidechainNodeChannel.getMempoolTxs(hashes).get
          responsePayload.size match {
            case 0 =>
              WebSocketServerEndpoint.sendError(requestId, requestType, 4, "Couldn't find mempool txs", session)
            case _ =>
              WebSocketServerEndpoint.sendMessage(RESPONSE_MESSAGE.code, requestId, requestType, responsePayload, session)
          }
        }

      case GET_RAW_MEMPOOL.code => // Get raw mempool
        val responsePayload = sidechainNodeChannel.getRawMempool().get
        responsePayload.size match {
          case 0 =>
            WebSocketServerEndpoint.sendError(requestId, requestType, 4, "Couldn't query mempool", session)
          case _ =>
            if (requestId == -1)
              WebSocketServerEndpoint.sendMessage(EVENT_MESSAGE.code, requestId, requestType, responsePayload, session)
            else
              WebSocketServerEndpoint.sendMessage(RESPONSE_MESSAGE.code, requestId, requestType, responsePayload, session)
        }

      case msgType =>
        System.out.println("Unknown message received with type = " + msgType)
    }

  }

  def processError(json: JsonNode, session: Session): Unit = {
    var requestId = -1
    if (json.has("requestId")) {
      requestId = json.get("requestId").asInt()
    }
    var requestType = -1
    if (json.has("requestType")) {
      requestType = json.get("requestType").asInt()
    }

    WebSocketServerEndpoint.sendError(requestId, requestType, 5, "WebSocket message error!", session)
  }
}

private object WebSocketServerEndpoint extends ScorexLogging {
  var sessions: util.ArrayList[Session] = new util.ArrayList[Session]()
  var sidechainNodeChannelImpl = new SidechainNodeChannelImpl();
  private val mapper = new ObjectMapper().registerModule(DefaultScalaModule)

  def addSession (session: Session): Unit = {
    this.sessions.add(session)
  }
  def removeSession (session: Session) : Unit = {
    this.sessions.remove(session)
  }

  def notifyMempoolChanged(): Unit = {
    val eventPayload = sidechainNodeChannelImpl.getRawMempool().get
    this.sessions.forEach(session =>{
        WebSocketServerEndpoint.sendMessage(EVENT_MESSAGE.code, -1, 2, eventPayload, session)
    })
  }

  def notifySemanticallySuccessfulModifier(): Unit = {
    val eventPayload = sidechainNodeChannelImpl.getBestBlock().get
    this.sessions.forEach(session =>{
        WebSocketServerEndpoint.sendMessage(EVENT_MESSAGE.code, -1, 0, eventPayload, session)
    })
  }

  // answerType is new field added to the default mainchain websocket events because to help the Explorer to understand
  // which type of response is included in the message
  def sendMessage(msgType: Int,  requestId: Int, answerType: Int, payload: ObjectNode, client: Session): Unit = {
    try {
      val json = mapper.createObjectNode()
      if (msgType == 0) { //send event message
        json.put("msgType", msgType)
        json.put("answerType", answerType)
        json.put("eventPayload", payload)
      } else { //send response message
        json.put("msgType", msgType)
        json.put("requestId", requestId)
        json.put("answerType", answerType)
        json.put("responsePayload", payload)
      }

      val message = json.toString

      client.getAsyncRemote().sendText(message, new SendHandler {
        override def onResult(sendResult: SendResult): Unit = {
          if (!sendResult.isOK) {
            log.info("Send message failed.")
          }
          else log.info("Message sent")
        }
      }
      )
    } catch {
      case e: Throwable => log.info("ERROR on sending message")
    }
  }

  def sendError(requestId: Int, answerType: Int, errorCode: Int, responsePayload: String, client: Session): Unit = {
      try {
        val json = mapper.createObjectNode()
        json.put("msgType", ERROR_MESSAGE.code)
        json.put("requestId", requestId)
        json.put("answerType", answerType)
        json.put("errorCode", errorCode)
        json.put("responsePayload", responsePayload)

        val message = json.toString
        client.getAsyncRemote().sendText(message, new SendHandler {
          override def onResult(sendResult: SendResult): Unit = {
            if (!sendResult.isOK) {
              log.info("Send message failed.")
            }
            else log.info("Message sent")
          }
        }
        )
      } catch {
        case e: Throwable => log.info("ERROR on sending message")
      }
    }

}
