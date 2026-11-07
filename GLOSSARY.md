# Bento

A CRM and back-office suite that small businesses and accounting practices run their sales, invoicing, cash and client work in.

## Identity

**Organization**:
One Bento customer's workspace. Every Membership and every business record belongs to exactly one.
_Avoid_: Tenant, workspace, company

**Account**:
A person who can sign in to Bento, identified by one verified email address.
_Avoid_: User, login, AppUser

**Membership**:
An Account's place in one Organization, carrying its role, team and whether it is active. An Account can hold Memberships in several Organizations.
_Avoid_: User, AppUser, seat

**Portal user**:
A client contact who signs in to one Organization's client portal. A Portal user is not an Account.
_Avoid_: Client account, customer user
