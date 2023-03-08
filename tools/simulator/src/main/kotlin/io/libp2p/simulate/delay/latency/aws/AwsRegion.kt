package io.libp2p.simulate.delay.latency.aws

enum class AwsRegion(
    val longName: String,
    val code: String,
) {
    AF_SOUTH_1("Africa (Cape Town)", "af-south-1"),
    AP_EAST_1("Asia Pacific (Hong Kong)", "ap-east-1"),
    AP_NORTHEAST_1("Asia Pacific (Tokyo)", "ap-northeast-1"),
    AP_NORTHEAST_2("Asia Pacific (Seoul)", "ap-northeast-2"),
    AP_NORTHEAST_3("Asia Pacific (Osaka)", "ap-northeast-3"),
    AP_SOUTH_1("Asia Pacific (Mumbai)", "ap-south-1"),
    AP_SOUTHEAST_1("Asia Pacific (Singapore)", "ap-southeast-1"),
    AP_SOUTHEAST_2("Asia Pacific (Sydney)", "ap-southeast-2"),
    CA_CENTRAL_1("Canada (Central)", "ca-central-1"),
    EU_CENTRAL_1("EU (Frankfurt)", "eu-central-1"),
    EU_NORTH_1("EU (Stockholm)", "eu-north-1"),
    EU_SOUTH_1("EU (Milan)", "eu-south-1"),
    EU_WEST_1("EU (Ireland)", "eu-west-1"),
    EU_WEST_2("EU (London)", "eu-west-2"),
    EU_WEST_3("EU (Paris)", "eu-west-3"),
    ME_SOUTH_1("Middle East (Bahrain)", "me-south-1"),
    SA_EAST_1("SA (São Paulo)", "sa-east-1"),
    US_EAST_1("US East (N. Virginia)", "us-east-1"),
    US_EAST_2("US East (Ohio)", "us-east-2"),
    US_WEST_1("US West (N. California)", "us-west-1"),
    US_WEST_2("US West (Oregon)", "us-west-2")
}