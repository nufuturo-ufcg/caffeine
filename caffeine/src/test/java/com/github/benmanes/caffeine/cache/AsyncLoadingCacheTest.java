/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.cache;

import static com.github.benmanes.caffeine.cache.testing.RemovalListenerVerifier.verifyRemovalListener;
import static com.github.benmanes.caffeine.cache.testing.StatsVerifier.verifyStats;
import static com.github.benmanes.caffeine.testing.Awaits.await;
import static com.github.benmanes.caffeine.testing.IsFutureValue.futureOf;
import static com.github.benmanes.caffeine.testing.IsInt.isInt;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.testng.Assert;
import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheExecutor;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ExecutorFailure;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.CheckNoStats;
import com.github.benmanes.caffeine.testing.Int;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.primitives.Ints;

/**
 * The test cases for the {@link AsyncLoadingCache} interface that simulate the most generic usages.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
@SuppressWarnings({"FutureReturnValueIgnored", "PreferJavaTimeOverload"})
public final class AsyncLoadingCacheTest {

  /* --------------- get --------------- */

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  public void get_null(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    cache.get(null);
  }

  @CacheSpec
  @Test(dataProvider = "caches")
  public void get_absent(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.get(context.absentKey()), is(futureOf(context.absentValue())));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.EXCEPTIONAL)
  public void get_absent_failure(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    CompletableFuture<Int> future = cache.get(context.absentKey());
    assertThat(future.isCompletedExceptionally(), is(true));
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.EXCEPTIONAL, executor = CacheExecutor.THREADED,
      executorFailure = ExecutorFailure.IGNORED)
  public void get_absent_failure_async(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    AtomicBoolean done = new AtomicBoolean();
    Int key = context.absentKey();
    var valueFuture = cache.get(key);
    valueFuture.whenComplete((r, e) -> done.set(true));

    await().untilTrue(done);
    await().until(() -> !cache.synchronous().asMap().containsKey(context.absentKey()));
    verifyStats(context, verifier -> verifier.hits(0).misses(1).success(0).failures(1));

    assertThat(valueFuture.isCompletedExceptionally(), is(true));
    assertThat(cache.getIfPresent(key), is(nullValue()));
    await().until(() -> cache.synchronous().estimatedSize(), is(context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void get_present(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    assertThat(cache.get(context.firstKey()), futureOf(context.firstKey().negate()));
    assertThat(cache.get(context.middleKey()), futureOf(context.middleKey().negate()));
    assertThat(cache.get(context.lastKey()), futureOf(context.lastKey().negate()));
    verifyStats(context, verifier -> verifier.hits(3).misses(0).success(0).failures(0));
  }

  /* --------------- getAll --------------- */

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_iterable_null(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    cache.getAll(null);
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = NullPointerException.class)
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_iterable_nullKey(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    cache.getAll(Collections.singletonList(null));
  }

  @CheckNoStats
  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_iterable_empty(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(List.of());
    assertThat(result.join().size(), is(0));
  }

  @CacheSpec
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void getAll_immutable(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    cache.getAll(context.absentKeys()).join().clear();
  }

  @CacheSpec(loader = Loader.BULK_NULL)
  @Test(dataProvider = "caches", expectedExceptions = CompletionException.class)
  public void getAll_absent_bulkNull(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    try {
      cache.getAll(context.absentKeys()).join();
    } finally {
      int misses = context.absentKeys().size();
      int loadFailures = context.loader().isBulk() ? 1 : (context.isAsync() ? misses : 1);
      verifyStats(context, verifier ->
          verifier.hits(0).misses(misses).success(0).failures(loadFailures));
    }
  }

  @CacheSpec(loader = { Loader.EXCEPTIONAL, Loader.BULK_EXCEPTIONAL })
  @Test(dataProvider = "caches", expectedExceptions = CompletionException.class)
  public void getAll_absent_failure(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    try {
      cache.getAll(context.absentKeys()).join();
    } finally {
      int misses = context.absentKeys().size();
      int loadFailures = context.loader().isBulk()
          ? 1
          : (context.isAsync() ? misses : 1);
      verifyStats(context, verifier ->
          verifier.hits(0).misses(misses).success(0).failures(loadFailures));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_absent(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.absentKeys()).join();

    int count = context.absentKeys().size();
    int loads = context.loader().isBulk() ? 1 : count;
    assertThat(result.size(), is(count));
    verifyStats(context, verifier -> verifier.hits(0).misses(count).success(loads).failures(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_present_partial(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var expect = new HashMap<Int, Int>();
    expect.put(context.firstKey(), context.firstKey().negate());
    expect.put(context.middleKey(), context.middleKey().negate());
    expect.put(context.lastKey(), context.lastKey().negate());
    var result = cache.getAll(expect.keySet()).join();

    assertThat(result, is(equalTo(expect)));
    verifyStats(context, verifier -> verifier.hits(expect.size()).misses(0).success(0).failures(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.BULK_NEGATIVE_EXCEEDS },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_exceeds(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var result = cache.getAll(context.absentKeys()).join();

    assertThat(result.keySet(), equalTo(context.absentKeys()));
    assertThat(cache.synchronous().estimatedSize(),
        is(greaterThan(context.initialSize() + context.absentKeys().size())));
    verifyStats(context, verifier -> verifier.hits(0).misses(result.size()).success(1).failures(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_duplicates(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var absentKeys = ImmutableSet.copyOf(Iterables.limit(context.absentKeys(),
        Ints.saturatedCast(context.maximum().max() - context.initialSize())));
    var keys = Iterables.concat(absentKeys, absentKeys,
        context.original().keySet(), context.original().keySet());
    var result = cache.getAll(keys).join();
    assertThat(result.keySet(), is(equalTo(ImmutableSet.copyOf(keys))));

    int loads = context.loader().isBulk() ? 1 : absentKeys.size();
    verifyStats(context, verifier ->
        verifier.hits(context.initialSize()).misses(absentKeys.size()).success(loads).failures(0));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_ordered_absent(
AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.absentKeys());
    Collections.shuffle(keys);

    var result = new ArrayList<>(cache.getAll(keys).join().keySet());
    assertThat(result, is(equalTo(keys)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.NEGATIVE, Loader.BULK_NEGATIVE },
      population = { Population.SINGLETON, Population.PARTIAL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_ordered_partial(
      AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    keys.addAll(context.absentKeys());
    Collections.shuffle(keys);

    var result = new ArrayList<>(cache.getAll(keys).join().keySet());
    assertThat(result, is(equalTo(keys)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = { Loader.EXCEPTIONAL, Loader.BULK_NEGATIVE_EXCEEDS },
      population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_ordered_present(
      AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    Collections.shuffle(keys);

    var result = new ArrayList<>(cache.getAll(keys).join().keySet());
    assertThat(result, is(equalTo(keys)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(loader = Loader.BULK_NEGATIVE_EXCEEDS,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAllPresent_ordered_exceeds(
      AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var keys = new ArrayList<>(context.original().keySet());
    keys.addAll(context.absentKeys());
    Collections.shuffle(keys);

    var result = new ArrayList<>(cache.getAll(keys).join().keySet());
    assertThat(result, is(equalTo(keys)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, compute = Compute.ASYNC,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void getAll_badLoader(CacheContext context) {
    var loader = new AsyncCacheLoader<Int, Int>() {
      @Override public CompletableFuture<Int> asyncLoad(Int key, Executor executor) {
        throw new IllegalStateException();
      }
      @Override public CompletableFuture<Map<Int, Int>> asyncLoadAll(
          Set<? extends Int> keys, Executor executor) {
        throw new LoadAllException();
      }
    };
    var cache = context.buildAsync(loader);

    try {
      cache.getAll(context.absentKeys()).join();
      Assert.fail();
    } catch (LoadAllException e) {
      assertThat(cache.synchronous().estimatedSize(), is(0L));
    }
  }

  @SuppressWarnings("serial")
  private static final class LoadAllException extends RuntimeException {}

  /* --------------- put --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL })
  public void put_replace(AsyncLoadingCache<Int, Int> cache, CacheContext context) {
    var value = context.absentValue().asFuture();
    for (Int key : context.firstMiddleLastKeys()) {
      cache.put(key, value);
      assertThat(cache.get(key), is(futureOf(context.absentValue())));
    }
    assertThat(cache.synchronous().estimatedSize(), is(context.initialSize()));

    int count = context.firstMiddleLastKeys().size();
    verifyRemovalListener(context, verifier -> verifier.hasOnly(count, RemovalCause.REPLACED));
  }

  /* --------------- refresh --------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      executor = CacheExecutor.THREADED, compute = Compute.ASYNC, values = ReferenceType.STRONG)
  public void refresh(CacheContext context) {
    var done = new AtomicBoolean();
    var cache = context.buildAsync((Int key) -> {
      await().untilTrue(done);
      return key.negate();
    });

    Int key = Int.valueOf(1);
    cache.synchronous().put(key, key);
    var original = cache.get(key);
    for (int i = 0; i < 10; i++) {
      context.ticker().advance(1, TimeUnit.SECONDS);
      cache.synchronous().refresh(key);

      var next = cache.get(key);
      assertThat(next, is(sameInstance(original)));
    }
    done.set(true);
    await().until(() -> cache.synchronous().getIfPresent(key), is(key.negate()));
  }

  @Test(dataProvider = "caches", timeOut = 5000) // Issue #69
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      executor = CacheExecutor.THREADED, compute = Compute.ASYNC, values = ReferenceType.STRONG)
  public void refresh_deadlock(CacheContext context) {
    var future = new CompletableFuture<Int>();
    var cache = context.buildAsync((Int k, Executor e) -> future);

    cache.synchronous().refresh(context.absentKey());
    var get = cache.get(context.absentKey());

    future.complete(context.absentValue());
    assertThat(get, futureOf(context.absentValue()));
  }

  /* --------------- AsyncCacheLoader --------------- */

  @Test(expectedExceptions = UnsupportedOperationException.class)
  public void asyncLoadAll() throws Exception {
    AsyncCacheLoader<Int, Int> loader = (key, executor) -> key.negate().asFuture();
    loader.asyncLoadAll(Set.of(), Runnable::run).join();
  }

  @Test
  public void asyncReload() throws Exception {
    AsyncCacheLoader<Int, Int> loader = (key, executor) -> key.negate().asFuture();
    var future = loader.asyncReload(Int.valueOf(1), Int.valueOf(2), Runnable::run);
    assertThat(future.join(), isInt(-1));
  }

  @SuppressWarnings("CheckReturnValue")
  @Test(expectedExceptions = NullPointerException.class)
  public void bulk_function_null() {
    Function<Set<? extends Int>, Map<Int, Int>> f = null;
    AsyncCacheLoader.bulk(f);
  }

  @Test
  public void bulk_function_absent() throws Exception {
    AsyncCacheLoader<Int, Int> loader = AsyncCacheLoader.bulk(keys -> Map.of());
    assertThat(loader.asyncLoadAll(Set.of(), Runnable::run).join(), is(Map.of()));
    assertThat(loader.asyncLoad(Int.valueOf(1), Runnable::run).join(), is(nullValue()));
  }

  @Test
  public void bulk_function_present() throws Exception {
    AsyncCacheLoader<Int, Int> loader = AsyncCacheLoader.bulk(keys -> {
      return keys.stream().collect(toMap(identity(), identity()));
    });
    assertThat(loader.asyncLoadAll(Int.setOf(1, 2), Runnable::run).join(),
        is(Int.mapOf(1, 1, 2, 2)));
    assertThat(loader.asyncLoad(Int.valueOf(1), Runnable::run).join(), isInt(1));
  }

  @SuppressWarnings("CheckReturnValue")
  @Test(expectedExceptions = NullPointerException.class)
  public void bulk_bifunction_null() {
    BiFunction<Set<? extends Int>, Executor, CompletableFuture<Map<Int, Int>>> f = null;
    AsyncCacheLoader.bulk(f);
  }

  @Test
  public void bulk_absent() throws Exception {
    BiFunction<Set<? extends Int>, Executor, CompletableFuture<Map<Int, Int>>> f =
        (keys, executor) -> CompletableFuture.completedFuture(Map.of());
    var loader = AsyncCacheLoader.bulk(f);
    assertThat(loader.asyncLoadAll(Set.of(), Runnable::run).join(), is(Map.of()));
    assertThat(loader.asyncLoad(Int.valueOf(1), Runnable::run).join(), is(nullValue()));
  }

  @Test
  public void bulk_present() throws Exception {
    BiFunction<Set<? extends Int>, Executor, CompletableFuture<Map<Int, Int>>> f =
        (keys, executor) -> {
          Map<Int, Int> results = keys.stream().collect(toMap(identity(), identity()));
          return CompletableFuture.completedFuture(results);
        };
    var loader = AsyncCacheLoader.bulk(f);
    assertThat(loader.asyncLoadAll(Int.setOf(1, 2), Runnable::run).join(),
        is(Int.mapOf(1, 1, 2, 2)));
    assertThat(loader.asyncLoad(Int.valueOf(1), Runnable::run).join(), isInt(1));
  }
}
