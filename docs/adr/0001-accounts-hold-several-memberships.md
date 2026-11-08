# An Account can hold Memberships in several Organizations; Portal users stay separate

A person signs in to Bento as one Account, identified by one verified email address, and belongs to each Organization through its own Membership. Before November 2026 Bento allowed one active Organization per person: signing in to one Organization deactivated that person's Memberships everywhere else, and accepting an invitation while signed in meant leaving the current Organization. We dropped that rule because the people Bento serves often work for several Organizations at once (an accountant with several client businesses, a consultant invited by several customers), and the deactivation silently cut them off from Organizations they still belonged to. Portal users, the client contacts who sign in to one Organization's client portal, stay a separate identity with their own sign-in, so a client contact never gains staff access because they share an email address with an Account.

## Considered options

- **One Organization per person (the old rule).** Simple, but it doesn't match how practices and consultants work, and it locked people out without telling them.
- **One sign-in per Organization, matched by email.** This is what `app_user` does today. We rejected it as the target because one person ends up with a separate password in each Organization, and verification, password reset and session revocation would each have to cover every copy.
- **Portal users as Accounts with a portal role.** Rejected: a client contact would be one role change away from staff access, and portal access has its own lifecycle (staff invite a contact for one client, and the contact never switches Organizations).

## Consequences

- An `app_user` row is a Membership: `AppUser` in the code means Membership. The password, verification state and password-change time move to a new `account` table that each Membership points at. Until then, a person with several Memberships has one row, and possibly a different password, in each Organization.
- Signing in with several active Memberships returns 409 with the choices, and the client signs in again with `organization_id`. The token's `sub` stays the Membership id, so tenant scoping doesn't change.
- Joining another Organization while signed in requires the token from the emailed invitation, never just a matching email address.
- No Account gets a session until its email address is verified. That depends on transactional email working in production, so mail comes before verification.
