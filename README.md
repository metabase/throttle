# Throttling

[![Clojars Project](https://clojars.org/metabase/throttle/latest-version.svg)](http://clojars.org/metabase/throttle)

[![Dependencies Status](http://jarkeeper.com/metabase/throttle/status.png)](http://jarkeeper.com/metabase/throttle)
[![Circle CI](https://circleci.com/gh/metabase/throttle.svg?style=svg)](https://circleci.com/gh/metabase/throttle)
[![GitHub license](https://img.shields.io/badge/license-3%E2%80%92Clause%20BSD-blue.svg)](https://raw.githubusercontent.com/metabase/throttle/master/LICENSE.txt)

A `Throttler` is a simple object used for throttling API endpoints or other code pathways. It keeps track of all calls with some value over some past period of time. If the number of calls with this value exceeds some threshold,
an exception is thrown, telling a user they must wait some period of time before trying again.

### Example

Let's consider a login endpoint that we want to make sure isn't vulnerable to a naughty hacker who tries to brute-force the password for a given email address :speak_no_evil:.
The basic concept here is to keep a list of logins attempts over the last hour. This list looks like:

```clojure
[["cam@metabase.com" 1438045261132] ; Unix timestamps (milliseconds)
 ["cam@metabase.com" 1438045260450]
 ["cam@metabase.com" 1438045259037]
 ["cam@metabase.com" 1438045258204]]
```

Every time there's a login attempt, push a new pair of `[email timestamp-milliseconds]` to the front of the list.
The list is thus automatically ordered by date, and we can drop the portion of the list with logins that are over
an hour old as needed.

Once we've passed some number of login attempts over the past hour (e.g. 5) for a given email address, calculate some delay before
they're allowed to try to log in again (e.g., 15 seconds). This number will increase exponentially as the number of
recent failures increases (e.g., 40 seconds for 6 failed attempts, 90 for 7 failed attempts, etc).

If applicable, calculate the time since the last failed attempt, and throw an exception telling the user the number
of seconds they must wait before trying again.

### Usage

Define a new throttler with `make-throttler`, overriding default settings as needed. The first parameter is the name of the field or value being checked and is used to generate appropriate error messages.

```clojure
(require '[throttle.core :as throttle])
(def email-throttler (throttle/make-throttler :email, :attempts-threshold 10))
```

Then call `check` at the appropriate point in your code with some value to apply throttling.

```clojure
(defn my-endpoint-fn [:as {{:keys [email]} :body}]
  (throttle/check email-throttler email)
  ...)
```

If you only want to throttle failures of some operation, like login attempts, you can use `with-throttling`.

```clojure
(defn my-login-fn [username password]
  (throttle/with-throttling [login-throttler username]
    (login username password)))
```

In the above case throttling will only kick in after `login` threw an exception `attempts-threshold` times.

`with-throttling` accepts multiple throttler-key pairs.

### Configuration

The following are options that can be passed to `make-throttler`:

*  `attempt-ttl-ms`
    Amount of time to keep an entry under consideration for throttling. (default: one hour)
*  `attempts-threshold`
    Number of attempts allowed with a given key before throttling is applied. (default: `10`)
*  `initial-delay-ms`
    Once throttling is in effect, initial delay before allowing another attempt. This grows according to `delay-exponent`. (default: 15 seconds)
*  `delay-exponent`
    For each subsequent failure past `attempts-threshold`, increase the delay to

    ```
    initial-delay-ms * (num-attempts-over-theshold ^ delay-exponent)
    ```

    e.g. if `initial-delay-ms` is `15` and `delay-exponent` is `2`, the first attempt past `attempts-threshold` will require the user to wait 15 seconds
    `(15 * 1^2)`, the next attempt after that 60 seconds `(15 * 2^2)`, then 135, and so on. (default: `1.5`)

### LICENSE

[3-clause BSD](https://raw.githubusercontent.com/metabase/throttle/master/LICENSE.txt)
