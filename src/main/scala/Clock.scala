package chess

import java.text.DecimalFormat

import Clock.{ Config, Player }

// All unspecified durations are expressed in seconds
protected sealed trait BaseClock {
  val config: Config
  val players: Color.Map[Player]

  protected def berserkPenalty =
    if (limitSeconds < config.estimateTotalIncSeconds) Centis(0)
    else Centis(limitSeconds * (100 / 2))

  def incrementOf(c: Color) = if (berserked(c)) Centis(0) else increment

  def berserked(c: Color) = players(c).berserk
  def lag(c: Color) = players(c).lag

  def emergSeconds = config.emergSeconds
  def estimateTotalIncrement = config.estimateTotalIncrement
  def estimateTotalSeconds = config.estimateTotalSeconds
  def estimateTotalTime = config.estimateTotalTime
  def increment = config.increment
  def incrementSeconds = config.incrementSeconds
  def limit = config.limit
  def limitInMinutes = config.limitInMinutes
  def limitSeconds = config.limitSeconds
}

case class Clock(
    config: Config,
    color: Color,
    players: Color.Map[Player],
    timer: Option[Timestamp]
) extends BaseClock {
  import Timestamp.now

  private def pending = timer.fold(Centis(0))(_ to now)

  private def rawRemaining(c: Color) = {
    val time = players(c).remaining
    if (c == color) time - pending else time
  }

  def remainingTime(c: Color) = rawRemaining(c) nonNeg

  private def timeSinceFlag(c: Color): Option[Centis] = rawRemaining(c) match {
    case s if s.centis <= 0 => Some(-s)
    case _ => None
  }

  def outoftimeWithGrace(c: Color) =
    timeSinceFlag(c).exists(_ > (lag(c) * 2 atMost Clock.maxLagToCompensate))

  def moretimeable(c: Color) = rawRemaining(c).centis < 100 * 60 * 60 * 2

  def isInit = players.all(_.elapsed.centis == 0)

  def isRunning = timer.isDefined

  def start = if (timer.isDefined) this else copy(timer = Some(now))

  def stop = timer.fold(this) { t =>
    copy(
      players = players.update(color, _.addElapsed(t to now)),
      timer = None
    )
  }

  def updatePlayer(c: Color, f: Player => Player) =
    copy(players = players.update(c, f))

  def step(metrics: MoveMetrics, withInc: Boolean = true) = timer match {
    case None => this
    case Some(t) => {
      val newT = now
      val elapsed = t to newT

      val lag = metrics.clientMoveTime.fold(metrics.clientLag getOrElse Centis(0))(elapsed - _)
      val lagComp = lag atMost Clock.maxLagToCompensate nonNeg
      val inc = if (withInc) incrementOf(color) else Centis(0)

      val adjustedMoveTime = ((elapsed - lagComp) nonNeg)

      copy(
        timer = Some(newT),
        players = players.update(color, p => {
          p.copy(
            elapsed = p.elapsed + adjustedMoveTime,
            limit = p.limit + inc
          )
        }),
        color = !color
      )
    }
  }

  def switch = copy(
    color = !color,
    timer = timer.map(_ => now)
  )

  def deinc = updatePlayer(color, _.giveTime(-incrementOf(color)))

  def takeback = switch.deinc

  def giveTime(c: Color, t: Centis) = updatePlayer(c, _.giveTime(t))

  def setRemainingTime(c: Color, centis: Centis) =
    updatePlayer(c, p => p.copy(elapsed = limit - centis))

  def goBerserk(c: Color) = updatePlayer(c, p => {
    if (p.berserk) p
    else p.copy(
      berserk = true,
      limit = p.limit - berserkPenalty
    )
  })
}

object Clock {
  private val limitFormatter = new DecimalFormat("#.##")

  case class Player(
      elapsed: Centis = Centis(0),
      lag: Centis = Centis(0),
      berserk: Boolean = false,
      limit: Centis
  ) {
    def remaining = limit - elapsed
    def giveTime(t: Centis) = copy(limit = limit + t)
    def addElapsed(t: Centis) = copy(elapsed = elapsed + t)
  }

  // All unspecified durations are expressed in seconds
  case class Config(limitSeconds: Int, incrementSeconds: Int) {

    def berserkable = incrementSeconds == 0 || limitSeconds > 0

    def emergSeconds = math.min(60, math.max(10, limitSeconds / 8))

    def estimateTotalIncrement = Centis.ofSeconds(estimateTotalIncSeconds)

    def estimateTotalIncSeconds = 40 * incrementSeconds

    def estimateTotalSeconds = limitSeconds + estimateTotalIncSeconds

    def estimateTotalTime = Centis.ofSeconds(estimateTotalSeconds)

    def hasIncrement = incrementSeconds > 0

    def increment = Centis.ofSeconds(incrementSeconds)

    def limit = Centis.ofSeconds(limitSeconds)

    def limitInMinutes = limitSeconds / 60d

    def toClock = Clock(this)

    def limitString = limitSeconds match {
      case l if l % 60 == 0 => l / 60
      case 15 => "¼"
      case 30 => "½"
      case 45 => "¾"
      case 90 => "1.5"
      case _ => limitFormatter.format(limitSeconds / 60d)
    }

    override def toString = s"$limitString+$incrementSeconds"
  }

  // [TimeControl "600+2"] -> 10+2
  def readPgnConfig(str: String): Option[Config] = str.split('+') match {
    case Array(initStr, incStr) => for {
      init <- parseIntOption(initStr)
      inc <- parseIntOption(incStr)
    } yield Config(init, inc)
    case _ => none
  }

  val minLimit = Centis(300)
  // no more than this time will be offered to the lagging player
  val maxLagToCompensate = Centis(100)

  def apply(limit: Int, increment: Int): Clock = apply(Config(limit, increment))

  def apply(config: Config): Clock = {
    val initTime = {
      if (config.limitSeconds == 0) config.increment atLeast minLimit
      else config.limit
    }

    Clock(
      config = config,
      color = White,
      players = Color.Map(_ => Player(limit = initTime)),
      timer = None
    )
  }
}
