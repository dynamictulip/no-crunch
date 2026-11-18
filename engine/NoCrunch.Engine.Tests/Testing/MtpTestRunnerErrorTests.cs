using Microsoft.Testing.Platform.ServerMode.Client;
using Microsoft.Testing.Platform.ServerMode.Client.Protocol.ServerMode;
using NoCrunch.Engine.Testing;

namespace NoCrunch.Engine.Tests.Testing;

[TestClass]
public class MtpTestRunnerErrorTests
{
    [TestMethod]
    public void Client_errors_that_merely_mention_12_or_protocol_are_crashes()
    {
        Exception[] errors =
        [
            new MtpServerClientException("Request timed out after 120s."),
            new MtpServerErrorException(ErrorCodes.InternalError, "Internal protocol error in process 4123."),
            new MtpServerClientException("Could not read /tmp/build-12/Tests.dll"),
        ];
        foreach (var e in errors)
            Assert.AreEqual(ProjectRunStatus.Crashed, MtpTestRunner.ToOutcome("p", e).Status, e.Message);
    }

    [TestMethod]
    public void Handshake_rejections_are_unsupported_versions()
    {
        // The server refuses our protocol version with its dedicated error code.
        Assert.IsTrue(MtpTestRunner.IsProtocolRejection(new MtpServerErrorException(ErrorCodes.ProtocolVersionNotSupported, "nope"), null));
        // The client refuses the version the server picked (a plain client exception from the handshake).
        Assert.IsTrue(MtpTestRunner.IsProtocolRejection(new MtpServerClientException("The server negotiated unsupported protocol version '9'."), null));
        // The server hangs up and exits with MTP's "incompatible protocol version" exit code.
        Assert.IsTrue(MtpTestRunner.IsProtocolRejection(new MtpServerConnectionClosedException(), 12));
    }

    [TestMethod]
    public void Other_handshake_failures_are_not_version_problems()
    {
        Assert.IsFalse(MtpTestRunner.IsProtocolRejection(new MtpServerConnectionClosedException("closed; pid 12"), null));
        Assert.IsFalse(MtpTestRunner.IsProtocolRejection(new MtpServerConnectionClosedException(), 1));
        Assert.IsFalse(MtpTestRunner.IsProtocolRejection(new MtpServerErrorException(ErrorCodes.InternalError, "protocol 12"), 12));
    }

    [TestMethod]
    public void Unsupported_version_exception_maps_to_UnsupportedMtpVersion()
    {
        var outcome = MtpTestRunner.ToOutcome("p", new UnsupportedMtpVersionException("too old", new MtpServerClientException("x")));
        Assert.AreEqual(ProjectRunStatus.UnsupportedMtpVersion, outcome.Status);
        Assert.AreEqual("too old", outcome.Error);
    }
}
