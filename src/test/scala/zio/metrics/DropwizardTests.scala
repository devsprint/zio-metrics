package zio.metrics

import scalaz.Scalaz._
import zio.{ DefaultRuntime, IO, Task }
import testz.{ assert, Harness, PureHarness }
import com.codahale.metrics.MetricRegistry

object DropwizardTests extends DefaultRuntime {

  val dropwizardMetrics = new DropwizardMetrics

  val testCounter: Task[Unit] = for {
    f <- dropwizardMetrics.counter(Label(DropwizardTests.getClass(), Array("test", "counter")))
    _ <- f(1)
    b <- f(2)
  } yield b

  val testGauge: (Option[Unit] => Long) => Task[Unit] = (f: Option[Unit] => Long) =>
    for {
      a <- dropwizardMetrics.gauge(Label(DropwizardTests.getClass(), Array("test", "gauge")))(
            f
          )
      r <- a(Some(()))
    } yield r

  val testTimer: Task[List[Double]] = for {
    t  <- dropwizardMetrics.timer(Label(DropwizardTests.getClass(), Array("test", "timer")))
    t1 = t.start
    l <- IO.foreach(
          List(
            Thread.sleep(1000L),
            Thread.sleep(1400L),
            Thread.sleep(1200L)
          )
        )(_ => t.stop(t1))
  } yield l

  val testHistogram: Task[Unit] = {
    import scala.math.Numeric.IntIsIntegral
    for {
      h <- dropwizardMetrics.histogram(Label(DropwizardTests.getClass(), Array("test", "histogram")))
      _ <- IO.foreach(List(h(10), h(25), h(50), h(57), h(19)))(_.unit)
    } yield ()
  }

  val testMeter: Task[Unit] = for {
    m <- dropwizardMetrics.meter(Label(DropwizardTests.getClass(), Array("test", "meter")))
    _ <- IO.foreach(Seq(1.0, 2.0, 3.0, 4.0, 5.0))(_ => m(1.0))
  } yield ()

  def tests[T](harness: Harness[T]): T = {
    import harness._
    section(
      test("counter increases by `inc` amount") { () =>
        unsafeRun(testCounter)
        val counter = dropwizardMetrics.registry
          .getCounters()
          .get(MetricRegistry.name(DropwizardTests.getClass().getName(), Array("test", "counter"): _*))
          .getCount

        assert(counter == 3)
      },
      test("gauge returns latest value") { () =>
        val tester: Option[Unit] => Long =
          (op: Option[Unit]) => op.map(_ => System.nanoTime()).get
        unsafeRun(testGauge(tester))
        val a1 = dropwizardMetrics.registry
          .getGauges()
          .get(MetricRegistry.name(DropwizardTests.getClass().getName(), Array("test", "gauge"): _*))
          .getValue
          .asInstanceOf[Long]

        val a2 = dropwizardMetrics.registry
          .getGauges()
          .get(MetricRegistry.name(DropwizardTests.getClass().getName(), Array("test", "gauge"): _*))
          .getValue
          .asInstanceOf[Long]

        assert(a1 < a2)
      },
      test("Timer called 3 times") { () =>
        unsafeRun(testTimer)
        val counter = dropwizardMetrics.registry
          .getTimers()
          .get(MetricRegistry.name(DropwizardTests.getClass().getName(), Array("test", "timer"): _*))
          .getCount

        assert(counter == 3)
      },
      test("Timer mean rate for 6 calls within bounds") { () =>
        unsafeRun(testTimer)
        val meanRate = dropwizardMetrics.registry
          .getTimers()
          .get(MetricRegistry.name(DropwizardTests.getClass().getName(), Array("test", "timer"): _*))
          .getMeanRate

        assert(meanRate > 0.78 && meanRate < 0.84)
      },
      test("Histogram 75th percentile is 50.0") { () =>
        unsafeRun(testHistogram)
        val perc75th = dropwizardMetrics.registry
          .getHistograms()
          .get(MetricRegistry.name(DropwizardTests.getClass().getName(), Array("test", "histogram"): _*))
          .getSnapshot
          .get75thPercentile

        assert(perc75th == 50.0)
      },
      test("Histogram 99.9th percentile is 57.0") { () =>
        unsafeRun(testHistogram)
        val perc99th = dropwizardMetrics.registry
          .getHistograms()
          .get(MetricRegistry.name(DropwizardTests.getClass().getName(), Array("test", "histogram"): _*))
          .getSnapshot
          .get999thPercentile

        assert(perc99th == 57.0)
      },
      test("Meter invoked 5 times") { () =>
        unsafeRun(testMeter)
        val counter = dropwizardMetrics.registry
          .getMeters()
          .get(MetricRegistry.name(DropwizardTests.getClass().getName(), Array("test", "meter"): _*))
          .getCount

        assert(counter == 5)
      },
      test("Meter mean rate within bounds") { () =>
        unsafeRun(testMeter)
        val meanRate = dropwizardMetrics.registry
          .getMeters()
          .get(MetricRegistry.name(DropwizardTests.getClass().getName(), Array("test", "meter"): _*))
          .getMeanRate

        println(meanRate)

        assert(meanRate > 600 && meanRate < 8000)
      }
    )
  }

  val harness: Harness[PureHarness.Uses[Unit]] =
    PureHarness.makeFromPrinter((result, name) => {
      println(s"${name.reverse.mkString("[\"", "\"->\"", "\"]:")} $result")
    })

  def main(args: Array[String]): Unit =
    tests(harness)((), Nil).print()
}
