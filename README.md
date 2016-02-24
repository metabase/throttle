# Throttling

[![Clojars Project](https://clojars.org/metabase/throttle/latest-version.svg)](http://clojars.org/metabase/throttle)

[![Dependencies Status](http://jarkeeper.com/metabase/throttle/status.png)](http://jarkeeper.com/metabase/throttle) [![Circle CI](https://circleci.com/gh/metabase/throttle.svg?style=svg)](https://circleci.com/gh/metabase/throttle)

A `Throttler` is a simple object used for throttling API endpoints or other code pathways. It keeps track of all calls to an API endpoint
with some value over some past period of time. If the number of calls with this value exceeds some threshold,
an exception is thrown, telling a user they must wait some period of time before trying again.

### Example

Let's consider the email throttling done by POST /api/session.
The basic concept here is to keep a list of failed logins over the last hour. This list looks like:

```clojure
(["cam@metabase.com" 1438045261132]
 ["cam@metabase.com" 1438045260450]
 ["cam@metabase.com" 1438045259037]
 ["cam@metabase.com" 1438045258204])
```

Every time there's a login attempt, push a new pair of `[email timestamp-milliseconds]` to the front of the list.
The list is thus automatically ordered by date, and we can drop the portion of the list with logins that are over
an hour old as needed.

Once a User has reached some number of login attempts over the past hour (e.g. 5), calculate some delay before
they're allowed to try to log in again (e.g., 15 seconds). This number will increase exponentially as the number of
recent failures increases (e.g., 40 seconds for 6 failed attempts, 90 for 7 failed attempts, etc).

If applicable, calucate the time since the last failed attempt, and throw an exception telling the user the number
of seconds they must wait before trying again.

### Usage

Define a new throttler with `make-throttler`, overriding default settings as needed.

```clojure
(require '[metabase.throttle :as throttle])
(def email-throttler (throttle/make-throttler :email, :attempts-threshold 10))
```

Then call `check` within the body of an endpoint with some value to apply throttling.

```clojure
(defn my-ring-endpoint-fn [:as {{:keys [email]} :body}]
  (throttle/check email-throttler email)
  ...)
```

### LICENSE

[LGPL](https://www.gnu.org/licenses/lgpl.txt)
