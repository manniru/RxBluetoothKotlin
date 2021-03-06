package com.vincentmasselis.devapp

import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.functions.Function
import io.reactivex.subjects.PublishSubject
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RxJavaUnitTest {

    private class DisconnectException : Throwable()

    @Test
    fun livingConnectionNormalTest() {
        println()
        println("-------- livingConnectionNormalTest")

        Observable.just(Unit)
            .mergeWith(
                Observable.timer(
                    150,
                    TimeUnit.MILLISECONDS
                ).flatMap { Observable.error<Unit>(DisconnectException()) })
            .doOnEach {
                println("${System.currentTimeMillis()} living connection value : ${it.value}")
                println("${System.currentTimeMillis()} living connection error : ${it.error}")
                println("${System.currentTimeMillis()} living connection isComplete : ${it.isOnComplete}")
            }
            .doOnDispose {
                println("${System.currentTimeMillis()} living connection disposed")
            }
            .switchMapSingle {
                Single.timer(50, TimeUnit.MILLISECONDS)
            }
            .onErrorResumeNext(Function {
                if (it is DisconnectException)
                    Observable.empty()
                else
                    Observable.error(it)
            })
            .firstElement()
            .doOnEvent { t1, t2 ->
                println("${System.currentTimeMillis()} I/O value : $t1")
                println("${System.currentTimeMillis()} I/O error : $t2")
                println("${System.currentTimeMillis()} I/O isComplete : ${t1 == null && t2 == null}")
            }
            .doOnDispose {
                println("I/O disposed")
            }
            .run { assertEquals(blockingGet(), 0L) }
    }

    @Test
    fun livingConnectionDisconnectedTest() {
        println()
        println("-------- livingConnectionDisconnectedTest")

        Observable.just(Unit)
            .mergeWith(
                Observable.timer(
                    50,
                    TimeUnit.MILLISECONDS
                ).flatMap { Observable.error<Unit>(DisconnectException()) })
            .doOnEach {
                println("${System.currentTimeMillis()} living connection value : ${it.value}")
                println("${System.currentTimeMillis()} living connection error : ${it.error}")
                println("${System.currentTimeMillis()} living connection isComplete : ${it.isOnComplete}")
            }
            .doOnDispose {
                println("${System.currentTimeMillis()} living connection disposed")
            }
            .switchMapSingle {
                Single.timer(150, TimeUnit.MILLISECONDS)
            }
            .onErrorResumeNext(Function {
                if (it is DisconnectException)
                    Observable.empty()
                else
                    Observable.error(it)
            })
            .firstElement()
            .doOnEvent { t1, t2 ->
                println("${System.currentTimeMillis()} I/O value : $t1")
                println("${System.currentTimeMillis()} I/O error : $t2")
                println("${System.currentTimeMillis()} I/O isComplete : ${t1 == null && t2 == null}")
            }
            .doOnDispose {
                println("I/O disposed")
            }
            .run { assertEquals(blockingGet(), null) }
    }

    @Test
    fun livingConnectionErrorTest() {
        println()
        println("-------- livingConnectionErrorTest")

        Observable.just(Unit)
            .mergeWith(Observable.timer(50, TimeUnit.MILLISECONDS).flatMap { Observable.error<Unit>(Throwable()) })
            .doOnEach {
                println("${System.currentTimeMillis()} living connection value : ${it.value}")
                println("${System.currentTimeMillis()} living connection error : ${it.error}")
                println("${System.currentTimeMillis()} living connection isComplete : ${it.isOnComplete}")
            }
            .doOnDispose {
                println("${System.currentTimeMillis()} living connection disposed")
            }
            .switchMapSingle {
                Single.timer(150, TimeUnit.MILLISECONDS)
            }
            .onErrorResumeNext(Function {
                if (it is DisconnectException)
                    Observable.empty()
                else
                    Observable.error(it)
            })
            .firstElement()
            .doOnEvent { t1, t2 ->
                println("${System.currentTimeMillis()} I/O value : $t1")
                println("${System.currentTimeMillis()} I/O error : $t2")
                println("${System.currentTimeMillis()} I/O isComplete : ${t1 == null && t2 == null}")
            }
            .doOnDispose {
                println("I/O disposed")
            }
            .run { assertFailsWith(Throwable::class) { blockingGet() } }
    }

    @Test
    fun takeUntilCompleteTest() {
        println()
        println("-------- takeUntilCompleteTest")

        Observable.interval(50, TimeUnit.MILLISECONDS)
            .takeUntil(Completable.timer(175, TimeUnit.MILLISECONDS).toObservable<Long>())
            .doOnEach {
                println("value : ${it.value}")
                println("error : ${it.error}")
                println("complete : ${it.isOnComplete}")
            }
            .run { assertEquals(blockingLast(), 2) }
    }

    private class ExceptedException : Throwable()

    @Test
    fun mergeWithErrorTest() {
        println()
        println("-------- mergeWithErrorTest")

        Flowable.interval(50, TimeUnit.MILLISECONDS)
            .takeUntil(Completable.timer(175, TimeUnit.MILLISECONDS).andThen(Flowable.error<Long>(ExceptedException())))
            .doOnEach {
                println("value : ${it.value}")
                println("error : ${it.error}")
                println("complete : ${it.isOnComplete}")
            }
            .run {
                assertFailsWith<ExceptedException> {
                    try {
                        blockingIterable().last()
                    } catch (ex: RuntimeException) {
                        throw ex.cause!!
                    }
                }
            }
    }

    @Test
    fun mergeWithCompleteTest() {
        println()
        println("-------- mergeWithCompleteTest")

        Flowable.interval(50, TimeUnit.MILLISECONDS)
            .takeUntil(Completable.timer(175, TimeUnit.MILLISECONDS).andThen(Flowable.just(0L)))
            .doOnEach {
                println("value : ${it.value}")
                println("error : ${it.error}")
                println("complete : ${it.isOnComplete}")
            }
            .run { assertEquals(3, blockingIterable().count()) }
    }

    @Test
    fun doOnSubscribe() {
        println()
        println("-------- doOnSubscribe")

        val subject = PublishSubject.create<Int>()

        Single.create<Int> { downStream ->
            downStream.setDisposable(
                subject.firstOrError().subscribe(
                    { downStream.onSuccess(it) },
                    { downStream.onError(it) })
            )
            subject.onNext(5)
        }
            .run { assertEquals(5, blockingGet()) }
    }

    @Test
    fun doOnSubscribePlusFail() {
        println()
        println("-------- doOnSubscribePlusFail")

        val subject = PublishSubject.create<Int>()

        Single
            .create<Int> { downStream ->
                downStream.setDisposable(
                    subject.firstOrError().subscribe(
                        { downStream.onSuccess(it) },
                        { downStream.onError(it) })
                )
                subject.onError(ExceptedException())
            }
            .run {
                assertFailsWith<ExceptedException> {
                    try {
                        blockingGet()
                    } catch (ex: RuntimeException) {
                        throw ex.cause!!
                    }
                }
            }
    }
}