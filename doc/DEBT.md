Technical debt
==============

We try to keep this as small as possible, but often there are little nags that should be
remembered when not yet addressed in the story worked on.

- Common exceptions can't be MicroStarException because that extends a Spring Exception. How to fix?
- Starting services on-demand sometimes starts a service twice. That is a race condition that needs to be fixed.
- 
